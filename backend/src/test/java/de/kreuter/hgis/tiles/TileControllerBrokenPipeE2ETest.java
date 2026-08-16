package de.kreuter.hgis.tiles;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.catalog.Layer;
import de.kreuter.hgis.catalog.LayerField;
import de.kreuter.hgis.catalog.LayerFieldRepository;
import de.kreuter.hgis.catalog.LayerRepository;
import de.kreuter.hgis.catalog.Project;
import de.kreuter.hgis.catalog.ProjectRepository;
import de.kreuter.hgis.common.ProblemDetailAdvice;
import de.kreuter.hgis.common.SqlIdentifier;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.slf4j.LoggerFactory;

/**
 * Reproduces the actual "Broken pipe" scenario end to end, over a real socket against a
 * real embedded server -- {@code MockMvc} never performs a real servlet-container write
 * and cannot exercise this path at all. Opens a connection, reads a few bytes of a large
 * tile response, then severs the TCP connection with RST ({@code SO_LINGER(0)}) while the
 * server is still writing, and checks what actually reaches the log: neither {@link
 * TileController} nor {@link ProblemDetailAdvice} should log this as an unhandled error.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class TileControllerBrokenPipeE2ETest {

	@LocalServerPort
	private int port;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private LayerRepository layerRepository;

	@Autowired
	private LayerFieldRepository layerFieldRepository;

	private String tableName;
	private Layer layer;

	@BeforeEach
	void setUp() {
		tableName = SqlIdentifier.tableName(UUID.randomUUID());
		String table = SqlIdentifier.quoteLayerTable(tableName);

		// A wide text column, so 50.000 features (the truncation limit bounds feature
		// COUNT, not attribute payload) add up to a response far bigger than any loopback
		// socket buffer -- needed for any real chance the server is still writing when the
		// client below severs the connection. Each value has to be distinct: a tile's
		// attribute values are a shared, deduplicated table (MvtTileDecoder's own class
		// comment), so 50.000 identical strings would cost one entry, not fifty thousand.
		jdbc.sql("""
				CREATE TABLE %s (
				    fid      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
				    padding  text,
				    geom     geometry(MultiPoint, 25832) NOT NULL
				)
				""".formatted(table)).update();
		jdbc.sql("CREATE INDEX ON %s USING GIST (geom)".formatted(table)).update();
		jdbc.sql("""
				INSERT INTO %s (padding, geom)
				SELECT left(repeat(md5(random()::text), 32), 1000),
				       ST_Multi(ST_SetSRID(ST_MakePoint(551000 + (random() * 2000), 5916000 + (random() * 2000)), 25832))
				FROM generate_series(1, :count)
				""".formatted(table))
				.param("count", 50_000)
				.update();
		jdbc.sql("ANALYZE " + table).update();

		Project project = projectRepository.saveAndFlush(
				new Project("Broken-Pipe-E2E-Test " + UUID.randomUUID(), null, 25832, "osm"));
		layer = layerRepository.saveAndFlush(
				new Layer(UUID.randomUUID(), project, "Broken-Pipe-Layer", tableName, "MULTIPOINT", 25832));
		layerFieldRepository.saveAndFlush(new LayerField(layer, "Padding", "padding", "text", 0));
		// Only a field the style actually classifies/labels by is carried into the tile
		// (LayerStyleService.tileColumns) -- a label renderer is the cheapest way to opt
		// "padding" in. The style resolves "field" against LayerField.columnName, not its
		// display name (LayerFields.byColumnName), so this has to spell the column itself.
		layer.setStyle("{\"labels\":{\"enabled\":true,\"field\":\"padding\"}}");
		layer = layerRepository.saveAndFlush(layer);
	}

	@AfterEach
	void tearDown() {
		layerRepository.delete(layer);
		jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(tableName)).update();
	}

	@Test
	@DisplayName("aborting the connection mid-tile-write is logged quietly, not as an unhandled error with a stack trace")
	void abortingConnectionMidWriteIsLoggedQuietly() throws Exception {
		Logger tileControllerLogger = (Logger) LoggerFactory.getLogger(TileController.class);
		ListAppender<ILoggingEvent> tileLog = new ListAppender<>();
		tileLog.start();
		tileControllerLogger.addAppender(tileLog);

		Logger adviceLogger = (Logger) LoggerFactory.getLogger(ProblemDetailAdvice.class);
		ListAppender<ILoggingEvent> adviceLog = new ListAppender<>();
		adviceLog.start();
		adviceLogger.addAppender(adviceLog);

		String path = "/api/layers/%s/tiles/0/0/0.mvt".formatted(layer.getId());

		try {
			// Sanity check first: the uninterrupted tile really is large enough to still be
			// in flight when the connection dies below -- without this, a passing test could
			// just as well mean "nothing was written yet", not "the write survived cleanly".
			HttpClient client = HttpClient.newHttpClient();
			HttpResponse<byte[]> whole = client.send(
					HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
					HttpResponse.BodyHandlers.ofByteArray());
			assertThat(whole.statusCode()).isEqualTo(200);
			assertThat(whole.body().length)
					.as("die volle Kachel muss deutlich groesser als ein Socket-Puffer sein, sonst ist der Test bedeutungslos")
					.isGreaterThan(1_000_000);

			try (Socket socket = new Socket()) {
				socket.connect(new InetSocketAddress("localhost", port), 2000);
				socket.getOutputStream().write(
						("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
								.getBytes(StandardCharsets.US_ASCII));
				socket.getOutputStream().flush();

				// Never drains the response -- the multi-MB tile has to overrun the receive
				// window long before the server finishes, so its write() is still in flight
				// (or blocked) when the connection is severed below with an RST rather than
				// a clean FIN.
				Thread.sleep(200);
				socket.setSoLinger(true, 0);
			}

			// Give the server a moment to notice the reset while finishing the write.
			Thread.sleep(1000);
		}
		finally {
			tileControllerLogger.detachAppender(tileLog);
			adviceLogger.detachAppender(adviceLog);
		}

		assertThat(tileLog.list)
				.as("TileController darf den abgebrochenen Schreibvorgang nicht als eigenen Fehler loggen")
				.noneMatch(e -> e.getLevel().isGreaterOrEqual(Level.WARN));

		assertThat(adviceLog.list)
				.as("mindestens eine Meldung wird erwartet -- der abgebrochene Schreibvorgang muss irgendwo ankommen")
				.isNotEmpty();
		for (ILoggingEvent event : adviceLog.list) {
			assertThat(event.getLevel())
					.as("ein abgebrochener Client ist der Normalfall, kein ERROR")
					.isEqualTo(Level.INFO);
			assertThat(event.getThrowableProxy())
					.as("kein Stacktrace fuer einen abgebrochenen Client")
					.isNull();
		}
	}
}
