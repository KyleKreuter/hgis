package de.kreuter.hgis.ingest;

import de.kreuter.hgis.ingest.spi.SourceFeature;
import de.kreuter.hgis.ingest.spi.SourceReader;
import de.kreuter.hgis.ingest.spi.SourceSchema;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * A {@link SourceReader} that takes its uploaded file with it when it closes.
 *
 * <p>An upload survives the inspection dialog on purpose -- changing the encoding must not
 * mean uploading half a gigabyte again -- but once an import has read it, it is spent.
 * Hanging the deletion off {@code close()} puts it exactly where the last read happens,
 * which is on the import's background thread, minutes after the request that started it
 * has long returned. {@link UploadJanitor} still catches the cases that never get that
 * far, such as an import that fails before it opens the feature stream.
 */
final class UploadConsumingReader implements SourceReader {

	private final SourceReader delegate;
	private final UploadStorage uploadStorage;
	private final Path uploadedFile;

	UploadConsumingReader(SourceReader delegate, UploadStorage uploadStorage, Path uploadedFile) {
		this.delegate = delegate;
		this.uploadStorage = uploadStorage;
		this.uploadedFile = uploadedFile;
	}

	@Override
	public SourceSchema schema() {
		return delegate.schema();
	}

	@Override
	public Stream<SourceFeature> features() {
		return delegate.features();
	}

	@Override
	public long skippedCount() {
		// Read after close by the import, which is why the delegate has to stay reachable.
		return delegate.skippedCount();
	}

	@Override
	public void close() {
		try {
			delegate.close();
		}
		finally {
			uploadStorage.cleanUp(uploadedFile);
		}
	}
}
