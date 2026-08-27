package de.kreuter.hgis;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Every writing endpoint of this backend is either reachable from the Python library or
 * named below as deliberately closed, with a reason. A new endpoint that is neither turns
 * this test red.
 *
 * <p>Why: "what can the interface do that an agent cannot" used to be a hand count, written
 * down twice in {@code TASKS.md} and stale again with the next endpoint. Aufgabe 21 closed
 * six such gaps at once on 27.08., and every one of them had gone unnoticed for weeks --
 * not because anyone decided against opening them, but because nobody was counting. This
 * test does the counting.
 *
 * <p>It is deliberately one-directional. It asks whether an endpoint has been *decided
 * about*, never whether it should be open -- that judgement stays with whoever adds the
 * endpoint, and lands in {@link #CLOSED_ON_PURPOSE} as a sentence rather than in a rule
 * here. A test that tried to decide would either be wrong or be a second copy of the
 * guard.
 *
 * <p>The endpoint list comes from Spring's own router, not from a regular expression over
 * the controller sources. The router is what actually serves requests; a pattern over
 * source text is an approximation, and its failure mode is the bad one -- an endpoint
 * written in a shape the pattern does not expect drops out of the comparison silently, and
 * the test stays green while the gap it exists to find is right there.
 *
 * <p>Same shape as {@link de.kreuter.hgis.catalog.DefaultSymbolCatalogueTest}: two places
 * that can drift apart, held together by a test. That one compares two documents and needs
 * no Spring; this one needs the context, because only the context knows the routes.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ParityTest {

	/**
	 * Relative to {@code backend/}, the working directory of both the CI job
	 * ({@code working-directory: backend}) and every local {@code ./mvnw} run -- the same
	 * assumption {@link de.kreuter.hgis.catalog.DefaultSymbolCatalogueTest} makes about the
	 * frontend's symbol catalogue.
	 */
	private static final Path GUARD_SOURCE = Path.of("../python/src/hgis/client.py");

	/** The four verbs that change something. GET is unrestricted on purpose, see {@code _ALLOWED}. */
	private static final Set<String> WRITING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

	/**
	 * Endpoints whose verb writes but which the library deliberately does not reach, and why.
	 *
	 * <p>The reason is the point of this map, not the key. Anyone adding an entry here is
	 * answering "why would an agent never need this", and the next person reads that answer
	 * instead of rediscovering the gap and wondering whether it was an oversight.
	 *
	 * <p>Kept apart from {@link #NOT_A_WRITE} because the two answer different questions.
	 * This one says "an agent should not do that"; the other says "that is not a write at
	 * all". Merging them would let a genuine gap hide behind a technicality.
	 */
	private static final Map<String, String> CLOSED_ON_PURPOSE = new LinkedHashMap<>();

	static {
		CLOSED_ON_PURPOSE.put(
				"POST /api/geoportal/catalog/refresh",
				"""
				Re-reads Hamburg's dataset catalogue into this installation. Application-wide \
				maintenance with no project behind it, and slow: an agent that wanted a fresh \
				catalogue would be making everyone else wait for it. Reading the catalogue is \
				open, which is what an agent actually needs.""");
		CLOSED_ON_PURPOSE.put(
				"POST /api/places/refresh",
				"""
				Rebuilds Hamburg's street and place index, one application-wide table shared by \
				every project (V10__place.sql, and the job's own project_id is null for exactly \
				this reason). Same argument as the catalogue refresh above -- searching places \
				is open, rebuilding the index is not an agent's business.""");
	}

	/**
	 * Endpoints that use a writing verb without changing anything, and why they use it.
	 *
	 * <p>{@link #WRITING_METHODS} is a good enough approximation of "this changes something"
	 * to be worth using, but it is only that. An entry belongs here when the verb is the
	 * only writing thing about the route -- and the entry has to say what forced the verb,
	 * because "it does not really write" is exactly what someone would claim about a route
	 * that does.
	 */
	private static final Map<String, String> NOT_A_WRITE = new LinkedHashMap<>();

	static {
		NOT_A_WRITE.put(
				"POST /api/layers/{}/export.geojson",
				"""
				The same export as its GET twin, which the guard already lets through with \
				every other read. POST only because a selection of thousands of fids does not \
				fit in a URL -- the body carries the list, and the response is the same stream \
				of features. Nothing is written; see ExportController.""");
	}

	/**
	 * The application's own router, named explicitly: the context also holds the actuator's
	 * {@code controllerEndpointHandlerMapping}, and its routes are not part of what an agent
	 * should reach or of what anyone decided about. Asking for the type alone fails outright
	 * rather than picking one, which is the better of the two behaviours -- but it still has
	 * to be answered here.
	 */
	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	private RequestMappingHandlerMapping handlerMapping;

	@Test
	void everyWritingEndpointIsEitherReachableOrDeliberatelyClosed() throws IOException {
		Set<String> reachable = guardPatterns();
		Set<String> endpoints = writingEndpoints();

		Set<String> undecided = new TreeSet<>(endpoints);
		undecided.removeAll(reachable);
		undecided.removeAll(CLOSED_ON_PURPOSE.keySet());
		undecided.removeAll(NOT_A_WRITE.keySet());

		assertThat(undecided)
				.withFailMessage(
						"""
						These writing endpoints are neither reachable from the Python library nor
						named as deliberately closed:

						%s

						Decide about each one. Either open it -- an entry in `_ALLOWED`, a named
						method on `Client` that builds its body, and an MCP tool -- or add it to
						ParityTest.CLOSED_ON_PURPOSE with a sentence saying why an agent would
						never need it. If its verb writes but the route does not, ParityTest.
						NOT_A_WRITE is the place, with a sentence saying what forced the verb.

						Do not add it to CLOSED_ON_PURPOSE merely to get this test green again:
						an entry without a real reason is worse than a red test, because it looks
						like a decision was made.

						For reference, %d endpoints are reachable, %d are closed on purpose and
						%d write nothing despite their verb.""",
						undecided.stream().map(one -> "  " + one).collect(Collectors.joining("\n")),
						reachable.size(),
						CLOSED_ON_PURPOSE.size(),
						NOT_A_WRITE.size())
				.isEmpty();
	}

	@Test
	void nothingIsListedAsClosedThatDoesNotExist() {
		Set<String> endpoints = writingEndpoints();

		Set<String> ghosts = new TreeSet<>(CLOSED_ON_PURPOSE.keySet());
		ghosts.addAll(NOT_A_WRITE.keySet());
		ghosts.removeAll(endpoints);

		assertThat(ghosts)
				.withFailMessage(
						"""
						These entries in CLOSED_ON_PURPOSE or NOT_A_WRITE name endpoints this
						backend no longer serves:

						%s

						Remove them. A closed-on-purpose list that outlives its endpoints stops
						being a record of decisions and starts being noise nobody trusts --
						and it hides the one entry that still matters.""",
						ghosts.stream().map(one -> "  " + one).collect(Collectors.joining("\n")))
				.isEmpty();
	}

	@Test
	void nothingIsBothReachableAndClosed() throws IOException {
		Set<String> reachable = guardPatterns();

		Set<String> both = new TreeSet<>(CLOSED_ON_PURPOSE.keySet());
		both.addAll(NOT_A_WRITE.keySet());
		both.retainAll(reachable);

		assertThat(both)
				.withFailMessage(
						"""
						These endpoints are listed as closed on purpose or as writing nothing, and
						are reachable from the Python library at the same time:

						%s

						One of the two is out of date. If the way was opened, drop its entry; the
						reason written there is no longer true, and leaving it in tells the next
						reader the opposite of what the code does.""",
						both.stream().map(one -> "  " + one).collect(Collectors.joining("\n")))
				.isEmpty();
	}

	// --- reading the two sides ---------------------------------------------

	/**
	 * Every writing route the running application serves, as {@code VERB /normalised/path}.
	 *
	 * <p>A mapping with several verbs or several patterns contributes one entry per
	 * combination -- that is what it actually serves, and treating it as one would let a
	 * verb slip through unexamined.
	 */
	private Set<String> writingEndpoints() {
		Set<String> found = new TreeSet<>();
		for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
			List<String> verbs = info.getMethodsCondition().getMethods().stream()
					.map(Enum::name)
					.filter(WRITING_METHODS::contains)
					.sorted()
					.toList();
			if (verbs.isEmpty()) {
				continue;
			}
			for (String pattern : patternsOf(info)) {
				for (String verb : verbs) {
					found.add(verb + " " + normalise(pattern));
				}
			}
		}
		return found;
	}

	private static List<String> patternsOf(RequestMappingInfo info) {
		if (info.getPathPatternsCondition() != null) {
			return info.getPathPatternsCondition().getPatternValues().stream()
					.sorted(Comparator.naturalOrder())
					.toList();
		}
		return info.getPatternValues().stream().sorted(Comparator.naturalOrder()).toList();
	}

	/**
	 * The writing entries of the library's {@code _ALLOWED}, in the same normalised shape.
	 *
	 * <p>Read as text rather than by running Python: this suite has no interpreter, and the
	 * list is a literal tuple of literal pairs, which is exactly the shape a pattern can
	 * read without guessing. The guard's own tests cover what the entries mean; this only
	 * needs to know which ones exist.
	 */
	private static Set<String> guardPatterns() throws IOException {
		assertThat(GUARD_SOURCE)
				.withFailMessage(
						"""
						The library's request guard is not where this test expects it:
						  %s
						Either the file moved, or this test is being run from somewhere other than
						`backend/`. Without it there is nothing to compare, and a backend endpoint
						no agent can reach would go unnoticed -- which is the one thing this test
						exists to prevent.""",
						GUARD_SOURCE.toAbsolutePath().normalize())
				.exists();

		String source = Files.readString(GUARD_SOURCE);

		String list = between(source, "_ALLOWED: tuple[tuple[str, str], ...] = (", "\n)");
		Set<String> patterns = new TreeSet<>();
		Matcher entry = Pattern.compile("\\(\"([A-Z]+)\",\\s*r f?\"([^\"]*)\"\\)|\\(\"([A-Z]+)\",\\s*rf?\"([^\"]*)\"\\)")
				.matcher(list);
		while (entry.find()) {
			String verb = entry.group(1) != null ? entry.group(1) : entry.group(3);
			String path = entry.group(2) != null ? entry.group(2) : entry.group(4);
			if (!WRITING_METHODS.contains(verb)) {
				continue;
			}
			patterns.add(verb + " " + normalise(path));
		}

		assertThat(patterns)
				.withFailMessage(
						"""
						No writing entries were read out of `_ALLOWED`. The list is still there --
						its opening line matched -- so either its shape changed or this test's
						pattern no longer fits it. Fix the pattern rather than the list: an empty
						read here would mark every endpoint as unreachable and bury the real
						answer under the noise.""")
				.isNotEmpty();

		return patterns;
	}

	private static String between(String source, String start, String end) {
		int from = source.indexOf(start);
		assertThat(from).withFailMessage("`_ALLOWED` does not start the way this test expects.").isNotNegative();
		int to = source.indexOf(end, from);
		assertThat(to).withFailMessage("`_ALLOWED` does not end the way this test expects.").isNotNegative();
		return source.substring(from + start.length(), to);
	}

	/**
	 * Both sides name their path variables differently -- Spring by role
	 * ({@code /{projectId}}), the guard by the shape it accepts ({@code /{_UUID}},
	 * {@code /\d+}). Neither difference says anything about reachability, so both collapse
	 * to {@code {}} before comparing.
	 */
	private static String normalise(String path) {
		return path.replaceAll("\\{[^}]*\\}", "{}").replace("\\d+", "{}");
	}
}
