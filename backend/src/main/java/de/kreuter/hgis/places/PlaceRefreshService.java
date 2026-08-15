package de.kreuter.hgis.places;

import de.kreuter.hgis.jobs.AsyncConfig;
import de.kreuter.hgis.jobs.JobService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Runs {@code POST /api/places/refresh}: fetches Hamburg's streets, districts and house
 * numbers, parses them and replaces the whole {@code place} table -- CONTRACT.md, measured
 * at 47 seconds for the streets alone, which is why this is a job rather than something the
 * request thread waits on.
 *
 * <p>The house numbers dominate that runtime: 302393 of them (measured 2026-08-15) arrive
 * over 31 requests of roughly 10.5&nbsp;MB each, about four minutes in total against under
 * one for streets and districts together. That is why every page updates the job's progress
 * as it lands -- a job that shows nothing moving for four minutes is indistinguishable, to
 * whoever is watching it, from one that has hung.
 *
 * <p>Fetch-and-parse happens before {@link PlaceWriter#replaceAll} is ever called, so the
 * one database transaction CONTRACT.md asks for ("erst leeren, dann schreiben, in einer
 * Transaktion") covers only the write -- a few seconds for on the order of ten thousand
 * rows -- and not the network round trip that makes up nearly all of the 47 seconds.
 * Holding a transaction open for that long would pin a connection and block autovacuum for
 * no benefit, the same reasoning {@code ingest.ImportService} gives for its own three-phase
 * split.
 *
 * <p>Reuses {@link AsyncConfig#IMPORT_EXECUTOR}: that pool's own doc already scopes it to
 * "imports (and later geoprocessing)", and a refresh is I/O bound in exactly the same way
 * an import is -- one more small, occasional background job did not seem to earn a
 * dedicated executor of its own.
 */
@Service
public class PlaceRefreshService {

	private static final Logger log = LoggerFactory.getLogger(PlaceRefreshService.class);

	private final HamburgPlaceFetcher fetcher;
	private final PlaceWriter writer;
	private final JobService jobService;

	PlaceRefreshService(HamburgPlaceFetcher fetcher, PlaceWriter writer, JobService jobService) {
		this.fetcher = fetcher;
		this.writer = writer;
		this.jobService = jobService;
	}

	/** Production entry point. Delegates to {@link #refresh}, which stays synchronous so
	 *  it can be exercised directly in tests without an async harness -- the same split
	 *  {@code ImportService} makes between {@code runImportAsync} and {@code runImport}. */
	@Async(AsyncConfig.IMPORT_EXECUTOR)
	public void refreshAsync(UUID jobId) {
		refresh(jobId);
	}

	/** Never throws: every failure path ends with the job marked FAILED and a readable
	 *  message, because nothing is left to report a failure to once this runs on a
	 *  background thread. */
	public void refresh(UUID jobId) {
		jobService.markRunning(jobId, null);
		try {
			List<ParsedPlace> streets = fetcher.fetchStrassen();
			List<ParsedPlace> districts = fetcher.fetchOrtsteile();

			// Asked for before the first page rather than after the last: it is what turns
			// the progress the pages below report into a fraction of something, and it costs
			// one request of a few hundred bytes.
			long addressTotal = fetcher.countHauskoordinaten();
			Long total = addressTotal < 0 ? null : streets.size() + districts.size() + addressTotal;

			List<ParsedPlace> all = new ArrayList<>(streets.size() + districts.size()
					+ (addressTotal < 0 ? 0 : (int) addressTotal));
			all.addAll(streets);
			all.addAll(districts);
			jobService.updateProgress(jobId, all.size(), total, 0);

			fetcher.fetchHauskoordinaten(addressTotal, page -> {
				all.addAll(page);
				jobService.updateProgress(jobId, all.size(), total, 0);
			});
			int addresses = all.size() - streets.size() - districts.size();

			int written = writer.replaceAll(all);
			jobService.updateProgress(jobId, written, (long) all.size(), 0);

			jobService.markSucceeded(jobId, written + " Orte aktualisiert ("
					+ streets.size() + " Straßen, " + districts.size() + " Ortsteile, "
					+ addresses + " Adressen)");
		}
		catch (Exception e) {
			log.error("Place refresh {} failed", jobId, e);
			jobService.markFailed(jobId, "Der Abzug der Hamburger Orte ist fehlgeschlagen: " + describe(e));
		}
	}

	private static String describe(Exception e) {
		String message = e.getMessage();
		return message != null && !message.isBlank() ? message : e.getClass().getSimpleName();
	}
}
