package de.kreuter.hgis;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;

/**
 * Guards Aufgabe 18's fix at its root cause rather than at the three spots it happened to
 * show up in.
 *
 * <p>{@code Enum.valueOf(String)} rejects an unknown token with only that token in its
 * message -- never what would have been accepted. {@code LayerService} and {@code
 * LayerFieldService} used to catch that and just forward the same one-sided message; the
 * fix wraps every rejection in {@link de.kreuter.hgis.common.GeometryType#unknownTypeMessage}
 * or {@link de.kreuter.hgis.common.FieldType#unknownTypeMessage}, both of which name every
 * value the enum actually has. Pinning those three call sites by file and line would only
 * catch a regression at exactly those three lines -- a fourth {@code valueOf} added next
 * year, on a different enum, would reintroduce the same bug and nothing here would notice.
 *
 * <p>So this scans the compiled backend for every {@code SomeEnum.valueOf(String)}
 * instruction instead, and requires that the method containing it -- or a method it calls,
 * followed transitively -- reaches {@code SomeEnum.values()} before it can return. That is
 * exactly the shape both fixes take: the catch block calls a message-building method, and
 * that method calls {@code values()}. A catch block that only repeats the rejected token
 * would fail this, at any file, at any line.
 *
 * <p>Two call sites are exempt, both reading {@code Layer.getGeometryType()} -- a column
 * only ever written through {@code LayerService#parseGeometryType}, which has already
 * validated it. A mismatch there is a database-integrity fault, not a token a caller typed
 * and could retry with a better guess, so naming the valid values would misdescribe the
 * failure rather than fix it. Aufgabe 18 is about tokens a caller supplies, not about this.
 *
 * <p>Reads {@code org.springframework.asm} rather than adding a bytecode-analysis library
 * for one test -- {@code spring-core} already carries its own shaded copy of ASM and is on
 * every test classpath here regardless.
 */
class EnumValueOfNamesValidValuesTest {

	/**
	 * Relative to {@code backend/}, same working-directory assumption {@code
	 * DefaultSymbolCatalogueTest} documents: both the CI job and every local {@code
	 * ./mvnw} invocation run from here, and Surefire's forked JVM inherits it.
	 */
	private static final Path CLASSES_DIR = Path.of("target/classes");

	/**
	 * {@code owner#methodName} of call sites this test does not require to reach {@code
	 * values()}. See the class comment for why: both resolve an already-validated,
	 * persisted {@code Layer.getGeometryType()}, not a client-supplied token.
	 */
	private static final Set<String> EXEMPT = Set.of(
			"de/kreuter/hgis/catalog/LayerFieldService#deleteField",
			"de/kreuter/hgis/tiles/TileController#tile");

	private record CallSite(String owner, String methodName, String methodDescriptor, String enumOwner) {

		String key() {
			return owner + "#" + methodName;
		}
	}

	@Test
	void everyEnumValueOfCallSiteNamesTheValidValues() throws IOException {
		assertThat(CLASSES_DIR)
				.as("%s does not exist -- run `mvn test-compile` (or `mvn test`) before this test, "
						+ "it scans the compiled backend, not the sources", CLASSES_DIR)
				.isDirectory();

		List<CallSite> callSites = scanForEnumValueOfCalls();

		// Sanity check on the scan itself: if this ever finds nothing, the scan broke
		// (wrong directory, ASM signature mismatch, ...) and every assertion below would
		// pass vacuously without having checked anything. At least the three named spots
		// from Aufgabe 18 plus the two exempt internal reads must show up.
		assertThat(callSites.size())
				.as("Scan for Enum.valueOf(String) call sites under target/classes found "
						+ "nothing (or too little) -- the scan itself is likely broken: %s", callSites)
				.isGreaterThanOrEqualTo(5);

		List<String> unguarded = new ArrayList<>();
		for (CallSite site : callSites) {
			if (EXEMPT.contains(site.key())) {
				continue;
			}
			if (!reachesValues(site.owner(), site.methodName(), site.methodDescriptor(),
					site.enumOwner(), new HashSet<>())) {
				unguarded.add(readable(site));
			}
		}

		assertThat(unguarded)
				.as("These call Enum.valueOf(String) but neither they nor a method they call "
						+ "reach that enum's values() -- their rejection would not say what a "
						+ "valid value looks like. Either route the failure through a "
						+ "*.unknownTypeMessage(raw)-shaped helper that calls values(), or add "
						+ "the call site to EXEMPT here with a reason.")
				.isEmpty();
	}

	private static String readable(CallSite site) {
		return site.owner().replace('/', '.') + "#" + site.methodName()
				+ " calls " + site.enumOwner().replace('/', '.') + ".valueOf(String)";
	}

	/** Every {@code SomeEnum.valueOf(String)} instruction in the compiled backend, with the method it sits in. */
	private static List<CallSite> scanForEnumValueOfCalls() throws IOException {
		List<CallSite> sites = new ArrayList<>();
		try (Stream<Path> classFiles = Files.walk(CLASSES_DIR)) {
			classFiles.filter(path -> path.toString().endsWith(".class")).forEach(path -> {
				try {
					collectEnumValueOfCalls(Files.readAllBytes(path), sites);
				}
				catch (IOException e) {
					throw new UncheckedIOExceptionForTest(path, e);
				}
			});
		}
		return sites;
	}

	private static void collectEnumValueOfCalls(byte[] classBytes, List<CallSite> sites) {
		new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {

			private String className;

			@Override
			public void visit(int version, int access, String name, String signature, String superName,
					String[] interfaces) {
				this.className = name;
			}

			@Override
			public MethodVisitor visitMethod(int access, String methodName, String descriptor,
					String signature, String[] exceptions) {
				return new MethodVisitor(Opcodes.ASM9) {
					@Override
					public void visitMethodInsn(int opcode, String owner, String name, String desc,
							boolean isInterface) {
						// An enum's generated valueOf(String) always returns its own type --
						// "(Ljava/lang/String;)L<owner>;" -- which the java.lang wrapper types'
						// same-named valueOf methods never do (Integer.valueOf returns
						// Ljava/lang/Integer;, never the caller's own class), and neither call
						// site here is compiled against those anyway: owner must be one of our
						// own compiled classes for target/classes to hold its .class file at all.
						if (opcode == Opcodes.INVOKESTATIC && "valueOf".equals(name)
								&& desc.equals("(Ljava/lang/String;)L" + owner + ";")
								&& owner.startsWith("de/kreuter/hgis/")) {
							sites.add(new CallSite(className, methodName, descriptor, owner));
						}
					}
				};
			}
		}, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
	}

	/**
	 * Whether {@code owner#methodName(methodDescriptor)} -- or a method under {@code
	 * de/kreuter/hgis} that it calls, followed transitively -- invokes {@code
	 * enumOwner.values()}.
	 */
	private static boolean reachesValues(String owner, String methodName, String methodDescriptor,
			String enumOwner, Set<String> visited) throws IOException {
		if (!visited.add(owner + "#" + methodName + methodDescriptor)) {
			// Already on the call stack we are exploring -- a cycle carries no new
			// evidence either way, so treat it as "nothing found down this path" rather
			// than recursing forever.
			return false;
		}

		Path classFile = CLASSES_DIR.resolve(owner + ".class");
		if (!Files.exists(classFile)) {
			// A call into a class this scan does not have -- e.g. a superclass method
			// inherited from outside de/kreuter/hgis. Nothing further to inspect.
			return false;
		}

		boolean[] found = {false};
		List<String[]> calledMethods = new ArrayList<>();
		new ClassReader(Files.readAllBytes(classFile)).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
					String[] exceptions) {
				if (!methodName.equals(name) || !methodDescriptor.equals(descriptor)) {
					return null;
				}
				return new MethodVisitor(Opcodes.ASM9) {
					@Override
					public void visitMethodInsn(int opcode, String insnOwner, String insnName, String insnDesc,
							boolean isInterface) {
						if (insnOwner.equals(enumOwner) && "values".equals(insnName)
								&& insnDesc.equals("()[L" + enumOwner + ";")) {
							found[0] = true;
						}
						else if (insnOwner.startsWith("de/kreuter/hgis/")) {
							calledMethods.add(new String[] {insnOwner, insnName, insnDesc});
						}
					}
				};
			}
		}, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

		if (found[0]) {
			return true;
		}
		for (String[] call : calledMethods) {
			if (reachesValues(call[0], call[1], call[2], enumOwner, visited)) {
				return true;
			}
		}
		return false;
	}

	/** Turns a checked {@link IOException} from inside a {@link Stream#forEach} lambda into an unchecked one. */
	private static final class UncheckedIOExceptionForTest extends RuntimeException {
		UncheckedIOExceptionForTest(Path path, IOException cause) {
			super("Failed to read " + path, cause);
		}
	}
}
