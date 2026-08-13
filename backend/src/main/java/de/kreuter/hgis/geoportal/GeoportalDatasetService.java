package de.kreuter.hgis.geoportal;

import de.kreuter.hgis.common.BadRequestException;
import de.kreuter.hgis.common.NotFoundException;
import de.kreuter.hgis.common.TypeMapper;
import de.kreuter.hgis.geoportal.GeoportalCatalogService.Snapshot;
import de.kreuter.hgis.geoportal.dto.GeoportalDtos;
import de.kreuter.hgis.ingest.reader.QueryablesSchema;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Answers CONTRACT.md 11.2 through 11.5: the catalog listing straight from the held
 * snapshot, and everything that needs a live call to the dataset's own OGC API Features
 * collection -- 11.4's field list and object count, and 11.5's bbox-filtered count.
 */
@Service
class GeoportalDatasetService {

	private final GeoportalCatalogService catalogService;
	private final OgcFeaturesClient ogcFeaturesClient;

	GeoportalDatasetService(GeoportalCatalogService catalogService, OgcFeaturesClient ogcFeaturesClient) {
		this.catalogService = catalogService;
		this.ogcFeaturesClient = ogcFeaturesClient;
	}

	GeoportalDtos.CatalogResponse list() {
		return toResponse(catalogService.current());
	}

	GeoportalDtos.CatalogResponse refresh() {
		return toResponse(catalogService.refresh());
	}

	/**
	 * CONTRACT.md 11.4. The expensive call, made only now that one dataset has actually
	 * been picked -- for a WMS-only entry (no OGC API Features binding) there is nothing
	 * live to ask, and the response carries {@link GeoportalDtos.DatasetSummary}'s fields
	 * with everything else null, exactly what CONTRACT.md 11.4 already allows for a field
	 * "the upstream catalog carries none" of.
	 */
	GeoportalDtos.DatasetDetail detail(String id) {
		GeoportalCatalogEntry entry = require(id);
		if (!entry.hasOgcFeatures()) {
			// Two entries end up here and neither has one collection to ask about: a service
			// listed as one row (CONTRACT.md 11.9), whose collections it answers with instead,
			// and a dataset with no OGC API Features access at all, which has nothing live to
			// be asked. Both carry DatasetSummary's fields with everything else null, exactly
			// what CONTRACT.md 11.4 already allows for a field "the upstream catalog carries
			// none" of.
			return new GeoportalDtos.DatasetDetail(
					entry.id(), entry.title(), null, entry.kind(), entry.agency(), entry.topic(),
					null, null,
					entry.attribution(), GeoportalLicense.NAME, GeoportalLicense.URL,
					entry.datasetUri(), entry.metadataUrl(), null, null, List.of(),
					entry.collectionCount(), toCollectionRefs(entry));
		}

		OgcFeaturesClient.CollectionInfo collectionInfo = ogcFeaturesClient.fetchCollection(entry.apiUrl(), entry.collection());
		String description = ogcFeaturesClient.fetchApiDescription(entry.apiUrl());
		List<QueryablesSchema.Field> queryableFields =
				ogcFeaturesClient.fetchQueryables(entry.apiUrl(), entry.collection(), entry.gfiAttributes());

		List<GeoportalDtos.Field> fields = queryableFields.stream()
				.map(f -> new GeoportalDtos.Field(f.technicalName(), f.title(),
						TypeMapper.toPostgresType(f.javaType()), f.enumValues()))
				.toList();
		String sourceFeatureIdField = queryableFields.stream()
				.filter(QueryablesSchema.Field::idField)
				.map(QueryablesSchema.Field::technicalName)
				.findFirst()
				.orElse(null);

		return new GeoportalDtos.DatasetDetail(
				entry.id(), entry.title(), description, entry.kind(), entry.agency(), entry.topic(),
				collectionInfo.itemCount(), collectionInfo.bboxWgs84(),
				entry.attribution(), GeoportalLicense.NAME, GeoportalLicense.URL,
				entry.datasetUri(), entry.metadataUrl(), collectionInfo.storageSrid(), sourceFeatureIdField, fields,
				entry.collectionCount(), List.of());
	}

	/** CONTRACT.md 11.9: what a service listed as one row offers to pick from; empty for everything else. */
	private static List<GeoportalDtos.CollectionRef> toCollectionRefs(GeoportalCatalogEntry entry) {
		return entry.collections().stream()
				.map(collection -> new GeoportalDtos.CollectionRef(collection.id(), collection.title()))
				.toList();
	}

	/** CONTRACT.md 11.5. */
	GeoportalDtos.CountResponse count(String id, double[] bbox4326) {
		GeoportalCatalogEntry entry = require(id);
		if (!entry.hasOgcFeatures()) {
			return new GeoportalDtos.CountResponse(null);
		}
		return new GeoportalDtos.CountResponse(ogcFeaturesClient.countMatching(entry.apiUrl(), entry.collection(), bbox4326));
	}

	GeoportalCatalogEntry require(String id) {
		return catalogService.find(id)
				.orElseThrow(() -> new NotFoundException("Geoportal-Datensatz " + id + " existiert nicht"));
	}

	/**
	 * Also used by {@link GeoportalImportController}: an import request that names nothing
	 * importable is a 400, not a 404 -- the id exists, it just does not name one collection.
	 *
	 * <p>The two reasons are told apart on purpose (CONTRACT.md 11.9). A service listed as
	 * one row holds collections the user can import right away and only has to pick one of;
	 * a dataset without OGC API Features access holds none this stage could read at all. One
	 * message for both would send the first user looking for a way out that the second one
	 * does not have.
	 */
	GeoportalCatalogEntry requireImportable(String id) {
		GeoportalCatalogEntry entry = require(id);
		if (entry.isService()) {
			throw new BadRequestException("Der Dienst '" + entry.title() + "' führt " + entry.collectionCount()
					+ " Sammlungen. Wählen Sie eine Sammlung aus und importieren Sie diese.");
		}
		if (!entry.hasOgcFeatures()) {
			throw new BadRequestException("Der Datensatz '" + entry.title()
					+ "' bietet keinen Objektzugang über OGC API Features und kann in dieser Stufe nicht importiert werden");
		}
		return entry;
	}

	private static GeoportalDtos.CatalogResponse toResponse(Snapshot snapshot) {
		List<GeoportalDtos.DatasetSummary> summaries = snapshot.entries().stream()
				.map(GeoportalDatasetService::toSummary)
				.toList();
		return new GeoportalDtos.CatalogResponse(snapshot.fetchedAt(), summaries);
	}

	/**
	 * {@code description}, {@code featureCount} and {@code bbox} are null for every entry
	 * here (CONTRACT.md 11.2 allows exactly this: "null when the upstream catalog carries
	 * none"). Neither of the two catalog files carries a short description; a per-dataset
	 * object count needs a live call to that dataset's own collection, which CONTRACT.md
	 * 11.4 reserves for the moment a dataset is actually selected; and the service
	 * directory's own {@code bbox} was measured, live, to be the same city-wide default for
	 * 1489 of 1570 checked entries -- not a real per-dataset extent, so showing it next to
	 * every row would be worse than showing nothing.
	 */
	private static GeoportalDtos.DatasetSummary toSummary(GeoportalCatalogEntry entry) {
		return new GeoportalDtos.DatasetSummary(
				entry.id(), entry.title(), null, entry.kind(), entry.agency(), entry.topic(), null, null,
				entry.collectionCount());
	}
}
