package de.kreuter.hgis.ingest;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cleans up uploads nobody ever imported -- the counterpart to
 * {@link de.kreuter.hgis.jobs.JobJanitor}, for the one thing an inspection leaves behind.
 *
 * <p>Inspecting deliberately creates no job, so a file that was inspected and then
 * abandoned (the user closed the dialog, or never liked what the preview showed) is
 * referenced by nothing at all. Without this, every abandoned dialog would cost up to the
 * 500 MB the application accepts, until the machine was rebooted.
 *
 * <p>Startup is the one moment when deleting everything is provably safe: an upload can
 * only be claimed through the id handed out during the request that stored it, and no
 * client holding one can have survived the restart in a state where the import would
 * still work. Afterwards only age can tell an abandoned upload from one whose dialog is
 * still open.
 */
@Component
public class UploadJanitor {

	private static final Logger log = LoggerFactory.getLogger(UploadJanitor.class);

	/** Long enough for a user who leaves the inspection dialog open over a lunch break. */
	static final Duration MAX_AGE = Duration.ofHours(6);

	private final UploadStorage uploadStorage;

	UploadJanitor(UploadStorage uploadStorage) {
		this.uploadStorage = uploadStorage;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void cleanUpAllUploads() {
		int removed = uploadStorage.deleteAll();
		if (removed > 0) {
			log.info("Removed {} upload(s) left over from a previous run", removed);
		}
	}

	@Scheduled(initialDelay = 1, fixedDelay = 1, timeUnit = TimeUnit.HOURS)
	public void cleanUpExpiredUploads() {
		int removed = uploadStorage.deleteOlderThan(MAX_AGE);
		if (removed > 0) {
			log.info("Removed {} upload(s) that were never imported within {}", removed, MAX_AGE);
		}
	}
}
