package de.kreuter.hgis.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.common.SqlIdentifier;
import de.kreuter.hgis.jobs.Job;
import de.kreuter.hgis.jobs.JobService;
import de.kreuter.hgis.jobs.dto.JobDtos;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Exercises the actual PostgreSQL 16-compatible DDL used for copying layer tables. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ProjectDuplicateServiceTest {

	@Autowired private ProjectDuplicateService duplicateService;
	@Autowired private ProjectRepository projects;
	@Autowired private LayerRepository layers;
	@Autowired private LayerFieldRepository fields;
	@Autowired private ProjectDeletionService deletion;
	@Autowired private JobService jobs;
	@Autowired private JdbcClient jdbc;

	private Project source;
	private String sourceTable;

	@BeforeEach
	void setUp() {
		source = projects.saveAndFlush(new Project("Kopie Quelle " + UUID.randomUUID(), "Beschreibung", 25832, "osm"));
		UUID layerId = UUID.randomUUID();
		sourceTable = SqlIdentifier.tableName(layerId);
		String table = SqlIdentifier.quoteLayerTable(sourceTable);
		jdbc.sql("""
				CREATE TABLE %s (
				  fid bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
				  geom geometry(MultiPolygon, 25832) NOT NULL,
				  titel text, hoehe integer
				)
				""".formatted(table)).update();
		jdbc.sql("CREATE INDEX " + SqlIdentifier.quoteColumn(sourceTable + "_geom_idx")
				+ " ON " + table + " USING GIST (geom)").update();
		jdbc.sql("INSERT INTO " + table + " (geom, titel, hoehe) VALUES "
				+ "(ST_Multi(ST_MakeEnvelope(1, 2, 3, 4, 25832)), 'Haus', 12),"
				+ "(ST_Multi(ST_MakeEnvelope(5, 6, 7, 8, 25832)), 'Halle', 25)").update();
		Layer layer = new Layer(layerId, source, "Gebäude", sourceTable, "MULTIPOLYGON", 25832);
		layer.setCopyMetadata(2, false, 4, 3, 18, "{\"kind\":\"fill\"}", null);
		layer = layers.saveAndFlush(layer);
		fields.saveAndFlush(new LayerField(layer, "Titel", "titel", "text", 0));
		fields.saveAndFlush(new LayerField(layer, "Höhe", "hoehe", "integer", 1));
	}

	@AfterEach
	void cleanUp() {
		projects.findAll().stream()
				.filter(project -> project.getName().startsWith("Kopie Quelle "))
				.forEach(project -> deletion.deleteProject(project.getId()));
	}

	@Test
	void copiesCatalogRowsPayloadIndexesAndIdentityWithoutTouchingSource() {
		Job job = jobs.create(source.getId(), Job.Type.DUPLICATE, null);
		duplicateService.runDuplicate(job.getId(), source.getId(), null);

		JobDtos.Response result = jobs.get(job.getId());
		assertThat(result.status()).isEqualTo("SUCCEEDED");
		Project target = projects.findById(result.outputProjectId()).orElseThrow();
		assertThat(target.getName()).isEqualTo(source.getName() + " (Kopie)");
		assertThat(target.getDescription()).isEqualTo("Beschreibung");

		Layer sourceLayer = layers.findByProjectOrdered(source.getId()).getFirst();
		Layer targetLayer = layers.findByProjectOrdered(target.getId()).getFirst();
		assertThat(targetLayer.getId()).isNotEqualTo(sourceLayer.getId());
		assertThat(targetLayer.getTableName()).isNotEqualTo(sourceTable);
		assertThat(targetLayer.getFeatureCount()).isEqualTo(2);
		assertThat(targetLayer.getDataVersion()).isEqualTo(1);
		assertThat(targetLayer.getStyleVersion()).isEqualTo(1);
		assertThat(fields.findByLayerIdOrderByOrdinalAsc(targetLayer.getId()))
				.extracting(LayerField::getColumnName).containsExactly("titel", "hoehe");

		String targetTable = SqlIdentifier.quoteLayerTable(targetLayer.getTableName());
		assertThat(jdbc.sql("SELECT COUNT(*) FROM " + targetTable).query(Long.class).single()).isEqualTo(2);
		assertThat(jdbc.sql("SELECT string_agg(titel || ':' || hoehe || ':' || ST_AsText(geom), ',' ORDER BY fid)"
				+ " FROM " + targetTable).query(String.class).single())
				.isEqualTo(jdbc.sql("SELECT string_agg(titel || ':' || hoehe || ':' || ST_AsText(geom), ',' ORDER BY fid)"
						+ " FROM " + SqlIdentifier.quoteLayerTable(sourceTable)).query(String.class).single());
		assertThat(jdbc.sql("""
				SELECT count(*) FROM pg_constraint c
				JOIN pg_class r ON r.oid = c.conrelid
				JOIN pg_namespace n ON n.oid = r.relnamespace
				WHERE n.nspname = 'gis_data' AND r.relname = :table AND c.contype = 'p'
				""").param("table", targetLayer.getTableName()).query(Long.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("SELECT count(*) FROM pg_indexes WHERE schemaname = 'gis_data' "
				+ "AND tablename = :table AND indexname = :index")
				.param("table", targetLayer.getTableName()).param("index", targetLayer.getTableName() + "_geom_idx")
				.query(Long.class).single()).isEqualTo(1);
		Long newFid = jdbc.sql("INSERT INTO " + targetTable
				+ " (geom, titel) VALUES (ST_Multi(ST_MakeEnvelope(9, 9, 10, 10, 25832)), 'Neu') RETURNING fid")
				.query(Long.class).single();
		assertThat(newFid).isGreaterThan(2);
		assertThat(jdbc.sql("SELECT COUNT(*) FROM " + SqlIdentifier.quoteLayerTable(sourceTable))
				.query(Long.class).single()).isEqualTo(2);
	}

	@Test
	void duplicatesAnEmptyProjectImmediately() {
		Project empty = projects.saveAndFlush(new Project("Kopie Quelle leer " + UUID.randomUUID(), null, 25832, "osm"));
		Job job = jobs.create(empty.getId(), Job.Type.DUPLICATE, null);
		duplicateService.runDuplicate(job.getId(), empty.getId(), null);
		assertThat(jobs.get(job.getId()).status()).isEqualTo("SUCCEEDED");
		assertThat(layers.findByProjectOrdered(jobs.get(job.getId()).outputProjectId())).isEmpty();
	}

	@Test
	void truncatesLongDefaultNamesAndKeepsCopyNumbersDistinct() {
		String longName = "x".repeat(200);
		assertThat(ProjectDuplicateTransactions.copyName(longName, 1)).hasSize(200).endsWith(" (Kopie)");
		assertThat(ProjectDuplicateTransactions.copyName(longName, 12)).hasSize(200).endsWith(" (Kopie 12)");
	}

	@Test
	void compensatesTheTargetWhenCopyingALayerFails() {
		UUID missingLayerId = UUID.randomUUID();
		Layer broken = new Layer(missingLayerId, source, "Fehlt", SqlIdentifier.tableName(missingLayerId),
				"MULTIPOLYGON", 25832);
		layers.saveAndFlush(broken);
		Job job = jobs.create(source.getId(), Job.Type.DUPLICATE, null);

		duplicateService.runDuplicate(job.getId(), source.getId(), "Fehlerziel " + UUID.randomUUID());

		JobDtos.Response result = jobs.get(job.getId());
		assertThat(result.status()).isEqualTo("FAILED");
		assertThat(result.outputProjectId()).isNotNull();
		assertThat(projects.findById(result.outputProjectId())).isEmpty();
	}
}
