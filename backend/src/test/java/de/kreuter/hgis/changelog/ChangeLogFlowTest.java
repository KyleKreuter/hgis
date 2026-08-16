package de.kreuter.hgis.changelog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.catalog.Layer;
import de.kreuter.hgis.catalog.LayerFieldService;
import de.kreuter.hgis.catalog.LayerRepository;
import de.kreuter.hgis.catalog.LayerService;
import de.kreuter.hgis.catalog.Project;
import de.kreuter.hgis.catalog.ProjectRepository;
import de.kreuter.hgis.catalog.dto.LayerDtos;
import de.kreuter.hgis.changelog.dto.ChangeLogDtos;
import de.kreuter.hgis.common.BadRequestException;
import de.kreuter.hgis.common.NotFoundException;
import de.kreuter.hgis.common.SqlIdentifier;
import de.kreuter.hgis.features.EditService;
import de.kreuter.hgis.features.dto.EditDtos;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The write change log end to end (CONTRACT.md "Schreibstufe" 1.2): every write path this
 * package covers has to leave exactly the entries the contract promises, and a batch of
 * deleted features has to leave its full rows behind, since that is the only fallback a
 * deleted object gets.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ChangeLogFlowTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final String SQUARE = """
			{"type":"Polygon","coordinates":[[[9.98,53.54],[9.99,53.54],[9.99,53.55],[9.98,53.55],[9.98,53.54]]]}
			""";

	@Autowired
	private LayerService layerService;

	@Autowired
	private LayerFieldService fieldService;

	@Autowired
	private EditService editService;

	@Autowired
	private ChangeLogService changeLogService;

	@Autowired
	private ChangeLogRepository changeLogRepository;

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private LayerRepository layerRepository;

	@Autowired
	private JdbcClient jdbc;

	private Project project;

	@BeforeEach
	void createProject() {
		project = projectRepository.saveAndFlush(
				new Project("Protokolltest " + UUID.randomUUID(), null, 25832, "osm"));
	}

	@AfterEach
	void dropProject() {
		// Every layer this class created hangs off `project`, trashed or not -- a purged
		// one has already dropped its own table and no longer shows up in either query.
		layerRepository.findByProjectOrdered(project.getId()).forEach(this::dropTable);
		layerRepository.findTrashedByProject(project.getId()).forEach(this::dropTable);
		projectRepository.deleteById(project.getId());
	}

	private void dropTable(Layer layer) {
		if (layer.isVectorLayer()) {
			jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(layer.getTableName())).update();
		}
	}

	private static JsonNode json(String geoJson) {
		return MAPPER.readTree(geoJson);
	}

	private Layer createLayer(String name, String clientName) {
		LayerDtos.Summary created = layerService.create(project.getId(),
				new LayerDtos.CreateRequest(name, "MULTIPOLYGON",
						List.of(new LayerDtos.CreateRequest.Field("Art", "TEXT"))),
				clientName);
		return layerRepository.findById(created.id()).orElseThrow();
	}

	private List<ChangeLogEntry> entriesFor(UUID layerId) {
		return changeLogRepository.findByProjectIdOrderByOccurredAtDescIdDesc(project.getId(), firstThousand())
				.stream()
				.filter(e -> layerId.equals(e.getLayerId()))
				.toList();
	}

	private static org.springframework.data.domain.Pageable firstThousand() {
		return org.springframework.data.domain.PageRequest.of(0, 1000);
	}

	@Test
	@DisplayName("creating a layer logs layer.create with the client's name")
	void layerCreateIsLogged() {
		Layer layer = createLayer("Erfassung", "cli-create");

		List<ChangeLogEntry> entries = entriesFor(layer.getId());
		assertThat(entries).hasSize(1);
		ChangeLogEntry entry = entries.get(0);
		assertThat(entry.getAction()).isEqualTo(ChangeLogAction.LAYER_CREATE);
		assertThat(entry.getClientName()).isEqualTo("cli-create");
		assertThat(entry.getAffectedCount()).isEqualTo(1);
		assertThat(entry.getLayerName()).isEqualTo("Erfassung");
		assertThat(entry.getProjectId()).isEqualTo(project.getId());
		assertThat(entry.getDeletedRows()).isNull();
	}

	@Test
	@DisplayName("patching a layer logs layer.update")
	void layerUpdateIsLogged() {
		Layer layer = createLayer("Wird geaendert", null);

		layerService.update(layer.getId(),
				new LayerDtos.UpdateRequest(null, false, null, null, null, null, null, null, null),
				"cli-update");

		List<ChangeLogEntry> entries = entriesFor(layer.getId());
		assertThat(entries).extracting(ChangeLogEntry::getAction)
				.contains(ChangeLogAction.LAYER_UPDATE);
	}

	@Test
	@DisplayName("delete (trash), restore and purge each log their own action")
	void trashRestoreAndPurgeAreLoggedSeparately() {
		Layer layer = createLayer("Lebenszyklus", null);
		UUID layerId = layer.getId();

		layerService.delete(layerId, "cli-a");
		layerService.restore(layerId, "cli-b");
		// Purge is only reached through the trash (LayerService#purge), so the layer has
		// to go back in before it can be emptied out of it.
		layerService.delete(layerId, "cli-a");
		layerService.purge(layerId, "cli-c");

		List<ChangeLogEntry> all = changeLogRepository
				.findByProjectIdOrderByOccurredAtDescIdDesc(project.getId(), firstThousand());
		List<ChangeLogEntry> forThisLayer = all.stream()
				.filter(e -> e.getLayerName().equals("Lebenszyklus"))
				.toList();

		// layer.create, two rounds of layer.delete/layer.restore/layer.delete, then
		// layer.purge -- purge nulled every one of this layer's layerId via ON DELETE SET
		// NULL, which is exactly why matching on layerName rather than layerId here.
		assertThat(forThisLayer).extracting(ChangeLogEntry::getAction)
				.contains(ChangeLogAction.LAYER_CREATE, ChangeLogAction.LAYER_DELETE,
						ChangeLogAction.LAYER_RESTORE, ChangeLogAction.LAYER_PURGE);

		ChangeLogEntry purgeEntry = forThisLayer.stream()
				.filter(e -> e.getAction().equals(ChangeLogAction.LAYER_PURGE))
				.findFirst().orElseThrow();
		assertThat(purgeEntry.getLayerId()).isNull();
		assertThat(purgeEntry.getClientName()).isEqualTo("cli-c");

		// The cascade (ON DELETE SET NULL) reached the *other* entries for this layer too --
		// their layerId is gone, but layerName still reads the layer's identity.
		ChangeLogEntry createEntry = forThisLayer.stream()
				.filter(e -> e.getAction().equals(ChangeLogAction.LAYER_CREATE))
				.findFirst().orElseThrow();
		assertThat(createEntry.getLayerId()).isNull();
		assertThat(createEntry.getLayerName()).isEqualTo("Lebenszyklus");
	}

	@Test
	@DisplayName("adding and deleting a field each log their own action")
	void fieldCreateAndDeleteAreLogged() {
		Layer layer = createLayer("Felder", null);

		LayerDtos.Field field = fieldService.addField(layer.getId(),
				new LayerDtos.AddFieldRequest("Baujahr", "INTEGER"), "cli-field-1");
		fieldService.deleteField(layer.getId(), field.id(), "cli-field-2");

		List<ChangeLogEntry> entries = entriesFor(layer.getId());
		assertThat(entries).extracting(ChangeLogEntry::getAction)
				.contains(ChangeLogAction.FIELD_CREATE, ChangeLogAction.FIELD_DELETE);
	}

	@Test
	@DisplayName("one edit batch logs insert, update and delete as three separate entries")
	void featureBatchLogsEachActionSeparately() {
		Layer layer = createLayer("Objekte", null);

		EditDtos.Response created = editService.apply(layer.getId(),
				new EditDtos.Request(
						List.of(new EditDtos.Create(-1, json(SQUARE), Map.of("art", "Eiche")),
								new EditDtos.Create(-2, json(SQUARE), Map.of("art", "Buche"))),
						null, null, false),
				"cli-batch-1");
		long firstFid = created.createdFids().get(-1L);
		long secondFid = created.createdFids().get(-2L);

		editService.apply(layer.getId(),
				new EditDtos.Request(null,
						List.of(new EditDtos.Update(firstFid, null, null, Map.of("art", "Fichte"))),
						List.of(secondFid), false),
				"cli-batch-2");

		List<ChangeLogEntry> entries = entriesFor(layer.getId());
		assertThat(entries).extracting(ChangeLogEntry::getAction)
				.contains(ChangeLogAction.FEATURE_INSERT, ChangeLogAction.FEATURE_UPDATE,
						ChangeLogAction.FEATURE_DELETE);

		ChangeLogEntry insertEntry = entries.stream()
				.filter(e -> e.getAction().equals(ChangeLogAction.FEATURE_INSERT)).findFirst().orElseThrow();
		assertThat(insertEntry.getAffectedCount()).isEqualTo(2);
		assertThat(insertEntry.getClientName()).isEqualTo("cli-batch-1");

		ChangeLogEntry deleteEntry = entries.stream()
				.filter(e -> e.getAction().equals(ChangeLogAction.FEATURE_DELETE)).findFirst().orElseThrow();
		assertThat(deleteEntry.getAffectedCount()).isEqualTo(1);
		assertThat(deleteEntry.getClientName()).isEqualTo("cli-batch-2");
		assertThat(deleteEntry.getDeletedRows()).isNotNull();

		JsonNode rows = json(deleteEntry.getDeletedRows());
		assertThat(rows.isArray()).isTrue();
		assertThat(rows).hasSize(1);
		JsonNode row = rows.get(0);
		assertThat(row.get("fid").asLong()).isEqualTo(secondFid);
		assertThat(row.get("properties").get("art").asString()).isEqualTo("Buche");
		// Promoted to its multi form on write, like every stored geometry (EditService).
		assertThat(row.get("geometry").get("type").asString()).isEqualTo("MultiPolygon");
	}

	@Test
	@DisplayName("an edit batch that touches nothing logs nothing")
	void emptyBatchPartsLogNothing() {
		Layer layer = createLayer("Leerlauf", null);

		// One creates-only batch already has one entry (layer.create); this second batch
		// only updates, so it must add exactly one more -- feature.update, no phantom
		// feature.insert or feature.delete for the two parts that touched nothing.
		EditDtos.Response created = editService.apply(layer.getId(),
				new EditDtos.Request(List.of(new EditDtos.Create(-1, json(SQUARE), Map.of())), null, null, false),
				null);
		long fid = created.createdFids().get(-1L);

		editService.apply(layer.getId(),
				new EditDtos.Request(null,
						List.of(new EditDtos.Update(fid, null, null, Map.of("art", "Ahorn"))),
						null, false),
				null);

		List<ChangeLogEntry> entries = entriesFor(layer.getId());
		assertThat(entries).extracting(ChangeLogEntry::getAction)
				.containsExactlyInAnyOrder(ChangeLogAction.LAYER_CREATE,
						ChangeLogAction.FEATURE_INSERT, ChangeLogAction.FEATURE_UPDATE);
	}

	@Test
	@DisplayName("GET .../changes returns newest first and rejects an out-of-range size")
	void listOrdersNewestFirstAndValidatesSize() {
		createLayer("A", null);
		createLayer("B", null);

		List<ChangeLogDtos.Entry> entries = changeLogService.list(project.getId(), 1000, false);
		assertThat(entries).hasSizeGreaterThanOrEqualTo(2);
		assertThat(entries.get(0).occurredAt()).isAfterOrEqualTo(entries.get(entries.size() - 1).occurredAt());
		assertThat(entries.get(0).deletedRows()).isNull();

		assertThatThrownBy(() -> changeLogService.list(project.getId(), 0, false))
				.isInstanceOf(BadRequestException.class);
		assertThatThrownBy(() -> changeLogService.list(project.getId(), 1001, false))
				.isInstanceOf(BadRequestException.class);
		assertThatThrownBy(() -> changeLogService.list(UUID.randomUUID(), 10, false))
				.isInstanceOf(NotFoundException.class);
	}

	@Test
	@DisplayName("includeDeletedRows=false hides captured rows even for a feature.delete entry")
	void listHidesDeletedRowsUnlessAskedFor() {
		Layer layer = createLayer("Sichtbarkeit", null);
		EditDtos.Response created = editService.apply(layer.getId(),
				new EditDtos.Request(List.of(new EditDtos.Create(-1, json(SQUARE), Map.of())), null, null, false),
				null);
		editService.apply(layer.getId(),
				new EditDtos.Request(null, null, List.of(created.createdFids().get(-1L)), false),
				null);

		List<ChangeLogDtos.Entry> withoutRows = changeLogService.list(project.getId(), 1000, false);
		ChangeLogDtos.Entry deleteWithoutRows = withoutRows.stream()
				.filter(e -> e.action().equals(ChangeLogAction.FEATURE_DELETE)).findFirst().orElseThrow();
		assertThat(deleteWithoutRows.deletedRows()).isNull();

		List<ChangeLogDtos.Entry> withRows = changeLogService.list(project.getId(), 1000, true);
		ChangeLogDtos.Entry deleteWithRows = withRows.stream()
				.filter(e -> e.action().equals(ChangeLogAction.FEATURE_DELETE)).findFirst().orElseThrow();
		assertThat(deleteWithRows.deletedRows()).isNotNull();
	}
}
