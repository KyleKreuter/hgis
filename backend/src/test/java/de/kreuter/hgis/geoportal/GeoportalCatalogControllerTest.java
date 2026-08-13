package de.kreuter.hgis.geoportal;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.kreuter.hgis.common.JsonFields;
import de.kreuter.hgis.common.NotFoundException;
import de.kreuter.hgis.common.ProblemDetailAdvice;
import de.kreuter.hgis.geoportal.dto.GeoportalDtos;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * CONTRACT.md 11.2 through 11.5, with {@link GeoportalDatasetService} mocked -- what this
 * suite actually proves is the routing, most of all that {@code id} containing a literal
 * {@code /} (CatalogLoader's own doing, e.g. {@code strassenbaumkataster/strassenbaumkataster_hh})
 * reaches the detail endpoint whole, and that the count endpoint's own {@code /count} suffix
 * still wins over the greedy {@code {id:.+}} pattern the detail mapping uses.
 */
@WebMvcTest(controllers = GeoportalCatalogController.class)
@Import({ ProblemDetailAdvice.class, GeoportalOutageAdvice.class })
class GeoportalCatalogControllerTest {

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private GeoportalDatasetService service;

	private static final String SLASHED_ID = "strassenbaumkataster/strassenbaumkataster_hh";

	@Test
	@DisplayName("GET /api/geoportal/datasets returns the held catalog, no query parameters needed")
	void listReturnsTheCatalog() throws Exception {
		given(service.list()).willReturn(new GeoportalDtos.CatalogResponse(Instant.parse("2026-08-12T09:00:00Z"),
				List.of(new GeoportalDtos.DatasetSummary(SLASHED_ID, "Straßenbaumkataster Hamburg", null,
						"FEATURES", "BUKEA", "Umwelt", null, null, 1))));

		mvc.perform(get("/api/geoportal/datasets"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.datasets", org.hamcrest.Matchers.hasSize(1)))
				.andExpect(jsonPath("$.datasets[0].id").value(SLASHED_ID))
				.andExpect(jsonPath("$.datasets[0].agency").value("BUKEA"))
				.andExpect(jsonPath("$.datasets[0].collectionCount").value(1));
	}

	@Test
	@DisplayName("POST /api/geoportal/catalog/refresh forces a reload")
	void refreshForcesAReload() throws Exception {
		given(service.refresh()).willReturn(new GeoportalDtos.CatalogResponse(Instant.now(), List.of()));

		mvc.perform(post("/api/geoportal/catalog/refresh"))
				.andExpect(status().isOk());
		verify(service).refresh();
	}

	@Test
	@DisplayName("the slash inside an OGC-API-Features-backed id reaches the detail endpoint whole")
	void detailReceivesTheFullSlashedId() throws Exception {
		given(service.detail(SLASHED_ID)).willReturn(new GeoportalDtos.DatasetDetail(
				SLASHED_ID, "Straßenbaumkataster Hamburg", null, "FEATURES", "BUKEA", "Umwelt",
				229876L, new double[] { 8.4, 53.4, 10.3, 54.0 },
				"Freie und Hansestadt Hamburg, Behörde für Umwelt, Klima, Energie und Agrarwirtschaft",
				GeoportalLicense.NAME, GeoportalLicense.URL, "https://registry.gdi-de.org/id/de.hh/x",
				"https://metaver.de/trefferanzeige?docuuid=x", 25832, "gid", List.of(), 1, List.of()));

		mvc.perform(get("/api/geoportal/datasets/" + SLASHED_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(SLASHED_ID))
				.andExpect(jsonPath("$.sourceFeatureIdField").value("gid"))
				.andExpect(jsonPath("$.storageSrid").value(25832));
		verify(service).detail(SLASHED_ID);
	}

	@Test
	@DisplayName("an unknown dataset id is a 404")
	void unknownDatasetIsNotFound() throws Exception {
		given(service.detail(SLASHED_ID)).willThrow(new NotFoundException("Geoportal-Datensatz " + SLASHED_ID + " existiert nicht"));

		mvc.perform(get("/api/geoportal/datasets/" + SLASHED_ID))
				.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("the /count suffix wins over the greedy detail pattern, and the id still arrives without it")
	void countRouteWinsOverDetailRouteAndStripsItsOwnSuffix() throws Exception {
		given(service.count(eq(SLASHED_ID), org.mockito.ArgumentMatchers.any()))
				.willReturn(new GeoportalDtos.CountResponse(696L));

		mvc.perform(get("/api/geoportal/datasets/" + SLASHED_ID + "/count")
						.param("bbox", "9.99,53.55,10.0,53.56"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.featureCount").value(696));

		verify(service).count(SLASHED_ID, new double[] { 9.99, 53.55, 10.0, 53.56 });
		// Proof the id itself never carries the "/count" suffix into the service layer.
		verify(service, org.mockito.Mockito.never()).detail(org.mockito.ArgumentMatchers.anyString());
	}

	@Test
	@DisplayName("count without bbox is a 400 (CONTRACT.md 11.5: bbox is required here)")
	void countWithoutBboxIsBadRequest() throws Exception {
		mvc.perform(get("/api/geoportal/datasets/" + SLASHED_ID + "/count"))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("a malformed bbox is a 400, not a 500")
	void malformedBboxIsBadRequest() throws Exception {
		mvc.perform(get("/api/geoportal/datasets/" + SLASHED_ID + "/count").param("bbox", "not,a,bbox"))
				.andExpect(status().isBadRequest());
	}

	// --- an outage upstream is not a fault of this program ------------------------------

	/**
	 * All three cases below used to reach the user as 500 "Interner Fehler" -- the timeout
	 * one after a full minute of waiting. That message names the wrong culprit: nothing here
	 * is broken, a service this program depends on did not answer, and the difference is
	 * exactly what tells a user whether retrying is worth anything.
	 */
	@Test
	@DisplayName("ein Serverfehler des Geoportals ist ein 502, kein interner Fehler")
	void anUpstreamServerFaultIsBadGateway() throws Exception {
		given(service.detail(SLASHED_ID))
				.willThrow(new GeoportalUnavailableException("Geoportal antwortete mit 503 auf https://api.hamburg.de/x"));

		mvc.perform(get("/api/geoportal/datasets/" + SLASHED_ID))
				.andExpect(status().isBadGateway())
				.andExpect(header().string("Retry-After", "60"))
				.andExpect(jsonPath("$.title").value("Geoportal nicht erreichbar"))
				.andExpect(jsonPath("$.detail", org.hamcrest.Matchers.containsString("Geoportal Hamburg")));
	}

	@Test
	@DisplayName("eine Zeitüberschreitung zum Geoportal ist ein 503, kein interner Fehler")
	void aTimeoutIsServiceUnavailable() throws Exception {
		given(service.detail(SLASHED_ID)).willThrow(new ResourceAccessException("Read timed out"));

		mvc.perform(get("/api/geoportal/datasets/" + SLASHED_ID))
				.andExpect(status().isServiceUnavailable())
				.andExpect(header().string("Retry-After", "60"))
				.andExpect(jsonPath("$.detail", org.hamcrest.Matchers.containsString("Geoportal Hamburg")));
	}

	@Test
	@DisplayName("ein fehlgeschlagenes erstes Laden des Katalogs ist ein 503, kein interner Fehler")
	void aFailedFirstCatalogLoadIsServiceUnavailable() throws Exception {
		given(service.list()).willThrow(new CatalogLoadException("Dienstverzeichnis antwortete mit 502"));

		mvc.perform(get("/api/geoportal/datasets"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.detail", org.hamcrest.Matchers.containsString("Geoportal Hamburg")));
	}

	/**
	 * The whole shape of the catalog answer (CONTRACT.md 11.2), envelope and row.
	 *
	 * <p>The row is where this matters most: the dialog filters by {@code agency} and
	 * {@code topic}, sorts by {@code title} and decides from {@code kind} whether a
	 * dataset can be imported at all. None of that fails loudly when a name changes --
	 * the filters simply stop matching and every entry falls into "Nur Kartenbild".
	 *
	 * <p>The fixture fills every field on purpose. A null one would still appear in the
	 * JSON, but a fixture that leaves fields out invites reading the assertion as the
	 * shape of this fixture rather than the shape of the DTO.
	 */
	@Test
	@DisplayName("the catalog response and its rows carry exactly the fields of the contract")
	void catalogResponseKeepsItsShape() throws Exception {
		given(service.list()).willReturn(new GeoportalDtos.CatalogResponse(Instant.parse("2026-08-12T09:00:00Z"),
				List.of(new GeoportalDtos.DatasetSummary(SLASHED_ID, "Straßenbaumkataster Hamburg",
						"Alle Straßenbäume der Stadt", "FEATURES", "BUKEA", "Umwelt", 229876L,
						new double[] { 8.4, 53.4, 10.3, 54.0 }, 1))));

		MvcResult result = mvc.perform(get("/api/geoportal/datasets"))
				.andExpect(status().isOk())
				.andReturn();

		JsonNode body = JsonFields.tree(result);
		JsonFields.assertFieldNames(body, "GeoportalDtos.CatalogResponse", "fetchedAt", "datasets");
		JsonFields.assertFieldNames(body.get("datasets").get(0), "GeoportalDtos.DatasetSummary",
				"id", "title", "description", "kind", "agency", "topic", "featureCount", "bbox",
				"collectionCount");
	}

	/**
	 * CONTRACT.md 11.9: a service listed as one row. The client decides from
	 * {@code collectionCount > 1} alone that a collection has to be picked first, so the
	 * field has to arrive as a number in the listing, not only in the detail.
	 */
	@Test
	@DisplayName("ein als eine Zeile gelisteter Dienst nennt seine Sammlungszahl in der Liste")
	void aServiceRowCarriesItsCollectionCount() throws Exception {
		given(service.list()).willReturn(new GeoportalDtos.CatalogResponse(Instant.parse("2026-08-12T09:00:00Z"),
				List.of(new GeoportalDtos.DatasetSummary("xplan", "XPlanungsdaten Hamburg", null, "BOTH", "BSW",
						"Regionen und Städte", null, null, 247))));

		mvc.perform(get("/api/geoportal/datasets"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.datasets[0].id").value("xplan"))
				.andExpect(jsonPath("$.datasets[0].collectionCount").value(247));
	}

	/** The detail answer (CONTRACT.md 11.4) -- the dialog reads every one of these. */
	@Test
	@DisplayName("the dataset detail carries exactly the fields of the contract")
	void datasetDetailKeepsItsShape() throws Exception {
		given(service.detail(SLASHED_ID)).willReturn(new GeoportalDtos.DatasetDetail(
				SLASHED_ID, "Straßenbaumkataster Hamburg", "Alle Straßenbäume der Stadt", "FEATURES",
				"BUKEA", "Umwelt", 229876L, new double[] { 8.4, 53.4, 10.3, 54.0 },
				"Freie und Hansestadt Hamburg, BUKEA", GeoportalLicense.NAME, GeoportalLicense.URL,
				"https://registry.gdi-de.org/id/de.hh/x", "https://metaver.de/trefferanzeige?docuuid=x",
				25832, "gid", List.of(), 1, List.of()));

		MvcResult result = mvc.perform(get("/api/geoportal/datasets/" + SLASHED_ID))
				.andExpect(status().isOk())
				.andReturn();

		JsonFields.assertFieldNames(JsonFields.tree(result), "GeoportalDtos.DatasetDetail",
				"id", "title", "description", "kind", "agency", "topic", "featureCount", "bbox",
				"attribution", "licenseName", "licenseUrl", "datasetUri", "metadataUrl", "storageSrid",
				"sourceFeatureIdField", "fields", "collectionCount", "collections");
	}

	/**
	 * CONTRACT.md 11.9: the detail of a service listed as one row is where its collections
	 * are picked. Their ids are dataset ids like any other -- a slash inside one has to
	 * survive the same route this suite already proves for a flat id, since asking for the
	 * chosen collection's detail is literally the next request the dialog makes.
	 */
	@Test
	@DisplayName("das Detail eines Dienstes liefert seine Sammlungen mit Kennung und Namen")
	void aServiceDetailListsItsCollections() throws Exception {
		given(service.detail("xplan")).willReturn(new GeoportalDtos.DatasetDetail(
				"xplan", "XPlanungsdaten Hamburg", null, "BOTH", "BSW", "Regionen und Städte", null, null,
				"Behörde für Stadtentwicklung und Wohnen (BSW)", GeoportalLicense.NAME, GeoportalLicense.URL,
				"https://registry.gdi-de.org/id/de.hh/d247341c-66e6-40fe-96dd-370b141ac473", null,
				null, null, List.of(), 247,
				List.of(new GeoportalDtos.CollectionRef("xplan/bp_baugrenze", "BP_BauGrenze"))));

		MvcResult result = mvc.perform(get("/api/geoportal/datasets/xplan"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.collectionCount").value(247))
				.andExpect(jsonPath("$.fields", org.hamcrest.Matchers.hasSize(0)))
				.andExpect(jsonPath("$.featureCount").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.collections[0].id").value("xplan/bp_baugrenze"))
				.andExpect(jsonPath("$.collections[0].title").value("BP_BauGrenze"))
				.andReturn();

		JsonFields.assertFieldNames(JsonFields.tree(result).get("collections").get(0),
				"GeoportalDtos.CollectionRef", "id", "title");
	}
}
