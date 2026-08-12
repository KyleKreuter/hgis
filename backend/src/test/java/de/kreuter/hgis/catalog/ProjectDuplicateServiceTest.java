package de.kreuter.hgis.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.common.SqlIdentifier;
import de.kreuter.hgis.jobs.Job;
import de.kreuter.hgis.jobs.JobService;
import de.kreuter.hgis.jobs.dto.JobDtos;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
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
		source = new Project("Kopie Quelle " + UUID.randomUUID(), "Beschreibung", 25832, "osm");
		source.setBasemapOpacity(0.4);
		source = projects.saveAndFlush(source);
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
		layer.setCopyMetadata(2, false, 4, 3, 18, "{\"kind\":\"fill\"}", "opentopo", 0.6, null, null, null);
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
		assertThat(target.getBasemapOpacity()).isEqualTo(0.4);

		Layer sourceLayer = layers.findByProjectOrdered(source.getId()).getFirst();
		Layer targetLayer = layers.findByProjectOrdered(target.getId()).getFirst();
		assertThat(targetLayer.getId()).isNotEqualTo(sourceLayer.getId());
		assertThat(targetLayer.getTableName()).isNotEqualTo(sourceTable);
		assertThat(targetLayer.getFeatureCount()).isEqualTo(2);
		assertThat(targetLayer.getDataVersion()).isEqualTo(1);
		assertThat(targetLayer.getStyleVersion()).isEqualTo(1);
		// The test one forgets: a layer's own basemap and its opacity must survive the
		// copy, or the duplicate silently falls back to the project's basemap.
		assertThat(targetLayer.getBasemap()).isEqualTo("opentopo");
		assertThat(targetLayer.getBasemapOpacity()).isEqualTo(0.6);
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

	/**
	 * CONTRACT.md phase 23.7: a duplicate must carry a layer's Geoportal provenance
	 * forward. Without this, a duplicated layer would show no attribution at all even
	 * though its data still originates from the Geoportal -- exactly the licence
	 * obligation (clause 2) that provenance exists to satisfy, silently unmet.
	 */
	@Test
	void duplicatingAProjectCarriesGeoportalProvenanceForward() {
		Layer gebaeude = layers.findByProjectOrdered(source.getId()).getFirst();
		gebaeude.setSource(
				"Freie und Hansestadt Hamburg, Behörde für Umwelt, Klima, Energie und Agrarwirtschaft",
				"Datenlizenz Deutschland – Namensnennung – Version 2.0",
				"https://www.govdata.de/dl-de/by-2-0",
				"https://registry.gdi-de.org/id/de.hh/x",
				"https://metaver.de/trefferanzeige?docuuid=x",
				"strassenbaumkataster/strassenbaumkataster_hh",
				"gid",
				Instant.parse("2026-08-12T09:14:00Z"));
		layers.saveAndFlush(gebaeude);

		Job job = jobs.create(source.getId(), Job.Type.DUPLICATE, null);
		duplicateService.runDuplicate(job.getId(), source.getId(), null);
		JobDtos.Response result = jobs.get(job.getId());
		assertThat(result.status()).isEqualTo("SUCCEEDED");

		Layer copy = layers.findByProjectOrdered(result.outputProjectId()).getFirst();
		assertThat(copy.getSourceAttribution())
				.isEqualTo("Freie und Hansestadt Hamburg, Behörde für Umwelt, Klima, Energie und Agrarwirtschaft");
		assertThat(copy.getSourceLicenseName()).isEqualTo("Datenlizenz Deutschland – Namensnennung – Version 2.0");
		assertThat(copy.getSourceLicenseUrl()).isEqualTo("https://www.govdata.de/dl-de/by-2-0");
		assertThat(copy.getSourceDatasetUri()).isEqualTo("https://registry.gdi-de.org/id/de.hh/x");
		assertThat(copy.getSourceMetadataUrl()).isEqualTo("https://metaver.de/trefferanzeige?docuuid=x");
		assertThat(copy.getSourceDatasetId()).isEqualTo("strassenbaumkataster/strassenbaumkataster_hh");
		assertThat(copy.getSourceFeatureIdField()).isEqualTo("gid");
		assertThat(copy.getSourceFetchedAt()).isEqualTo(Instant.parse("2026-08-12T09:14:00Z"));
	}

	/**
	 * CONTRACT.md phase 23, decision E6: a Geoportal layer carries a non-unique index on the
	 * service's own feature id, and stage 5's reconcile is what it exists for. {@code LIKE
	 * ... EXCLUDING INDEXES} drops every index of the source, not only the two whose
	 * schema-wide names would have collided, and only the primary key and the GiST index are
	 * put back by name -- so the copy used to lose exactly the index the reconcile needs.
	 * Nothing reports that: the copy answers every query, only slowly, and only once it is
	 * large enough to matter.
	 */
	@Test
	void duplicatingALayerCarriesItsAttributeIndexForward() {
		// What TableCreator.createAttributeIndex leaves on a layer imported from the Geoportal.
		jdbc.sql("CREATE INDEX " + SqlIdentifier.quoteColumn(sourceTable + "_titel_idx") + " ON "
				+ SqlIdentifier.quoteLayerTable(sourceTable) + " (titel)").update();

		Job job = jobs.create(source.getId(), Job.Type.DUPLICATE, null);
		duplicateService.runDuplicate(job.getId(), source.getId(), null);
		JobDtos.Response result = jobs.get(job.getId());
		assertThat(result.status()).isEqualTo("SUCCEEDED");

		Layer copy = layers.findByProjectOrdered(result.outputProjectId()).getFirst();
		assertThat(indexedAttributeColumnsOf(copy.getTableName()))
				.as("die Kopie behält den Index auf dem Kennfeld")
				.containsExactly("titel");
		assertThat(indexedAttributeColumnsOf(sourceTable))
				.as("und die Quelle bleibt unberührt")
				.containsExactly("titel");
		// The geometry index is recreated under the copy's own name, so both still exist.
		assertThat(jdbc.sql("SELECT count(*) FROM pg_indexes WHERE schemaname = 'gis_data' AND tablename = :table")
				.param("table", copy.getTableName()).query(Long.class).single())
				.as("Primärschlüssel, GiST und Attributindex")
				.isEqualTo(3);
	}

	/** Every plain, single-column index on an ordinary attribute of one layer table. */
	private List<String> indexedAttributeColumnsOf(String tableName) {
		return jdbc.sql("""
				SELECT a.attname
				FROM pg_index i
				JOIN pg_class c ON c.oid = i.indrelid
				JOIN pg_namespace n ON n.oid = c.relnamespace
				JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = i.indkey[0]
				WHERE n.nspname = 'gis_data' AND c.relname = :table
				  AND NOT i.indisprimary AND i.indnatts = 1 AND a.attname <> 'geom'
				ORDER BY a.attname
				""").param("table", tableName).query(String.class).list();
	}

	/** A layer never touched by {@code setSource} must copy as having no provenance -- null,
	 *  not some accidental default, matching {@code copiesCatalogRowsPayloadIndexesAndIdentityWithoutTouchingSource}. */
	@Test
	void duplicatingALayerWithoutProvenanceLeavesTheCopyWithoutItToo() {
		Job job = jobs.create(source.getId(), Job.Type.DUPLICATE, null);
		duplicateService.runDuplicate(job.getId(), source.getId(), null);
		JobDtos.Response result = jobs.get(job.getId());

		Layer copy = layers.findByProjectOrdered(result.outputProjectId()).getFirst();
		assertThat(copy.getProvenance()).isNull();
	}

	/**
	 * CONTRACT.md phase 21: a duplicate must not silently lose any of its clip masks, or
	 * the mode each clips in -- the one-mask-per-project limit is gone, so a project may
	 * well hold several. Checking only the mode strings would not be enough -- a mode
	 * that survived the copy but landed at the wrong z-index would still cut the wrong
	 * layers, so this also confirms both masks still clip a layer above them once all
	 * three are in the target project.
	 */
	@Test
	void duplicatingAProjectWithSeveralClipMasksKeepsThemAndTheirEffect() {
		Layer gebaeude = layers.findByProjectOrdered(source.getId()).getFirst();
		gebaeude.setClipMode("outsideClipped");
		layers.saveAndFlush(gebaeude);

		UUID fundamentId = UUID.randomUUID();
		String fundamentTable = SqlIdentifier.tableName(fundamentId);
		String quotedFundamentTable = SqlIdentifier.quoteLayerTable(fundamentTable);
		jdbc.sql("""
				CREATE TABLE %s (
				  fid  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
				  geom geometry(MultiPolygon, 25832) NOT NULL
				)
				""".formatted(quotedFundamentTable)).update();
		jdbc.sql("CREATE INDEX " + SqlIdentifier.quoteColumn(fundamentTable + "_geom_idx")
				+ " ON " + quotedFundamentTable + " USING GIST (geom)").update();
		Layer fundament = new Layer(fundamentId, source, "Fundament", fundamentTable, "MULTIPOLYGON", 25832);
		fundament.setZIndex(gebaeude.getZIndex() - 1);
		fundament.setClipMode("insideWhole");
		layers.saveAndFlush(fundament);

		UUID aboveId = UUID.randomUUID();
		String aboveTable = SqlIdentifier.tableName(aboveId);
		String quotedAboveTable = SqlIdentifier.quoteLayerTable(aboveTable);
		jdbc.sql("""
				CREATE TABLE %s (
				  fid  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
				  geom geometry(MultiPolygon, 25832) NOT NULL
				)
				""".formatted(quotedAboveTable)).update();
		jdbc.sql("CREATE INDEX " + SqlIdentifier.quoteColumn(aboveTable + "_geom_idx")
				+ " ON " + quotedAboveTable + " USING GIST (geom)").update();
		Layer above = new Layer(aboveId, source, "Dach", aboveTable, "MULTIPOLYGON", 25832);
		above.setZIndex(gebaeude.getZIndex() + 1);
		layers.saveAndFlush(above);

		Job job = jobs.create(source.getId(), Job.Type.DUPLICATE, null);
		duplicateService.runDuplicate(job.getId(), source.getId(), null);
		JobDtos.Response result = jobs.get(job.getId());
		assertThat(result.status()).isEqualTo("SUCCEEDED");

		List<Layer> copiedLayers = layers.findByProjectOrdered(result.outputProjectId());
		List<Layer> maskCopies = copiedLayers.stream().filter(Layer::isMask).toList();
		Layer aboveCopy = copiedLayers.stream()
				.filter(l -> l.getName().equals("Dach")).findFirst().orElseThrow();

		assertThat(maskCopies).hasSize(2);
		assertThat(maskCopies.stream().collect(Collectors.toMap(Layer::getName, Layer::getClipMode)))
				.containsEntry("Gebäude", "outsideClipped")
				.containsEntry("Fundament", "insideWhole");
		// Preserved z-index is what makes the masks affect the same layer in the copy as
		// in the source -- without it, the mode alone would be a lie about what clips.
		for (Layer maskCopy : maskCopies) {
			assertThat(aboveCopy.getZIndex()).isGreaterThan(maskCopy.getZIndex());
		}
		assertThat(aboveCopy.clipVersion(maskCopies))
				.as("Layer über den Masken muss in der Kopie wirklich beschnitten werden")
				.isNotZero();
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
