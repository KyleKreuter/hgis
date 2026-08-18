package de.kreuter.hgis.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.common.SqlIdentifier;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code project.catalog_version} itself, at the database level -- independent of {@link
 * CatalogTouch} and the live channel, which only ever read the number this trigger
 * produces (V14__catalog_version.sql, plan "Der Live-Kanal meldet auch
 * Datenaenderungen").
 *
 * <p>The one write path this class exists to pin down is {@link
 * LayerRepository#bumpDataVersion}: a bulk {@code @Modifying} JPQL {@code UPDATE}, which
 * Hibernate executes without ever loading the {@link Layer} entity it touches. Neither a
 * Java counter bumped by hand next to it, nor a JPA entity listener (@PostUpdate and
 * friends fire only for a dirty-checked flush), would ever see this write -- only a
 * database trigger does, because it fires on the SQL statement itself. This is the
 * concrete, measured case the migration's own comment points at.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CatalogVersionTriggerTest {

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private LayerRepository layerRepository;

	@Autowired
	private LayerFieldRepository fieldRepository;

	@Autowired
	private JdbcClient jdbc;

	private Project project;
	private Layer layer;

	@BeforeEach
	void createProjectAndLayer() {
		project = projectRepository.saveAndFlush(
				new Project("Katalogversion-Testprojekt " + UUID.randomUUID(), null, 25832, "osm"));
		UUID layerId = UUID.randomUUID();
		layer = layerRepository.saveAndFlush(
				new Layer(layerId, project, "Layer " + layerId, SqlIdentifier.tableName(layerId),
						"MULTIPOINT", 25832));
	}

	@AfterEach
	void dropProject() {
		layerRepository.findByProjectOrdered(project.getId())
				.forEach(l -> jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(l.getTableName()))
						.update());
		projectRepository.deleteById(project.getId());
	}

	private long catalogVersion() {
		return jdbc.sql("SELECT catalog_version FROM gis_meta.project WHERE id = :id")
				.param("id", project.getId())
				.query(Long.class)
				.single();
	}

	@Test
	@DisplayName("creating a layer already bumped the version once -- the baseline every other test compares against")
	void creatingALayerBumpsTheVersion() {
		// The layer created in createProjectAndLayer() is the only write so far, so the
		// version is already above the column's own DEFAULT 1 -- proof enough that a plain
		// INSERT into layer fires the trigger, without needing a second layer just to
		// observe a change.
		assertThat(catalogVersion()).isGreaterThan(1);
	}

	/**
	 * {@code @Transactional} here for two different reasons on the two tests below --
	 * Spring Data requires an active transaction around any {@code @Modifying} query at
	 * all, and around a plain {@code flush()} it is what keeps {@link #layer} the very
	 * same managed instance from {@link #createProjectAndLayer} instead of one silently
	 * detached the moment that method's own {@code saveAndFlush} returned -- a
	 * {@code flush()} on a detached entity has nothing pending and writes nothing, which a
	 * first version of this test found the hard way: it passed for the wrong reason,
	 * asserting a version that had not actually moved. Rolled back afterwards, same as any
	 * other {@code @Transactional} test.
	 */
	@Test
	@Transactional
	@DisplayName("a bulk @Modifying UPDATE -- the exact shape ImportTransactions.writeBatch uses -- still bumps the version")
	void bulkModifyingUpdateBumpsTheVersion() {
		long before = catalogVersion();

		// LayerRepository#bumpDataVersion is a JPQL bulk update: Hibernate sends it
		// straight to the database without loading the Layer entity first, so no
		// @PostUpdate callback and no dirty-checked flush ever happens for it. If the
		// counter still moves, it can only be the trigger -- nothing on the Java side ran.
		layerRepository.bumpDataVersion(layer.getId());

		assertThat(catalogVersion()).isEqualTo(before + 1);
	}

	@Test
	@Transactional
	@DisplayName("an ordinary entity flush -- setVisible plus flush, the shape most write paths use -- bumps the version")
	void entityFlushBumpsTheVersion() {
		long before = catalogVersion();

		layer.setVisible(false);
		layerRepository.flush();

		assertThat(catalogVersion()).isEqualTo(before + 1);
	}

	@Test
	@DisplayName("adding a field bumps the version through the layer_field trigger")
	void addingAFieldBumpsTheVersion() {
		long before = catalogVersion();

		fieldRepository.saveAndFlush(new LayerField(layer, "Baujahr", "baujahr", "integer", 0));

		assertThat(catalogVersion()).isEqualTo(before + 1);
	}

	@Test
	@DisplayName("deleting a field bumps the version too")
	void deletingAFieldBumpsTheVersion() {
		LayerField field = fieldRepository.saveAndFlush(new LayerField(layer, "Baujahr", "baujahr", "integer", 0));
		long before = catalogVersion();

		fieldRepository.delete(field);
		fieldRepository.flush();

		assertThat(catalogVersion()).isEqualTo(before + 1);
	}

	@Test
	@DisplayName("purging a layer that still has fields does not fail -- the field's own trigger finds no project any more and does nothing")
	void purgingALayerWithFieldsDoesNotFail() {
		fieldRepository.saveAndFlush(new LayerField(layer, "Baujahr", "baujahr", "integer", 0));
		jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(layer.getTableName())).update();
		long before = catalogVersion();

		// layer_field's ON DELETE CASCADE fires inside this same statement; the field
		// trigger's lookup of the layer's project_id finds the layer already gone by then.
		// Nothing here may throw, and the layer's own DELETE still bumps the version once.
		assertThatCode(() -> {
			layerRepository.delete(layer);
			layerRepository.flush();
		}).doesNotThrowAnyException();

		assertThat(catalogVersion()).isEqualTo(before + 1);
	}

	@Test
	@DisplayName("a write to the project's own row, unrelated to any layer, leaves the catalog version untouched")
	void aPlainProjectWriteDoesNotBumpTheVersion() {
		long before = catalogVersion();

		project.setName("Umbenannt " + UUID.randomUUID());
		projectRepository.flush();

		assertThat(catalogVersion()).isEqualTo(before);
	}
}
