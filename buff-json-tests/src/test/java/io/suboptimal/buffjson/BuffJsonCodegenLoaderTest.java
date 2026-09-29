package io.suboptimal.buffjson;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import io.suboptimal.buffjson.internal.FastInput;
import io.suboptimal.buffjson.internal.codegen.RuntimeCodegen;
import io.suboptimal.buffjson.proto.TestNesting;

/**
 * Generated readers are defined next to the message class, in whatever class
 * loader it came from: here a child loader that has its own copy of the test
 * messages while protobuf-java and buff-json come from the parent, as in an
 * application server where the application's classes are isolated.
 */
class BuffJsonCodegenLoaderTest {

	/**
	 * Defines the classes of the test messages itself and delegates everything
	 * else.
	 */
	private static final class IsolatingLoader extends ClassLoader {
		IsolatingLoader(ClassLoader parent) {
			super(parent);
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			if (!name.startsWith("io.suboptimal.buffjson.proto.")) {
				return super.loadClass(name, resolve);
			}
			synchronized (getClassLoadingLock(name)) {
				Class<?> type = findLoadedClass(name);
				if (type == null) {
					try (InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
						if (in == null) {
							throw new ClassNotFoundException(name);
						}
						byte[] bytes = in.readAllBytes();
						type = defineClass(name, bytes, 0, bytes.length);
					} catch (IOException e) {
						throw new ClassNotFoundException(name, e);
					}
				}
				if (resolve) {
					resolveClass(type);
				}
				return type;
			}
		}
	}

	@Test
	void aReaderIsDefinedInTheLoaderOfItsMessageAndWorksThere() throws Exception {
		Assumptions.assumeTrue(RuntimeCodegen.available(), "needs the Class-File API (Java 24+)");
		ClassLoader isolated = new IsolatingLoader(getClass().getClassLoader());
		Class<?> message = isolated.loadClass(TestNesting.class.getName());
		assertNotSame(TestNesting.class, message);
		assertSame(isolated, message.getClassLoader());

		var decoder = RuntimeCodegen.fastDecoder(message, false);
		assertNotNull(decoder, () -> String.valueOf(RuntimeCodegen.failure(message, false)));
		assertSame(isolated, decoder.getClass().getClassLoader());
		// the nested message's reader is defined there too
		assertSame(isolated, isolated.loadClass(decoder.getClass().getName()).getClassLoader());

		TestNesting original = TestNesting.newBuilder()
				.setNested(io.suboptimal.buffjson.proto.NestedMessage.newBuilder().setValue(7).setName("nested"))
				.addRepeatedNested(io.suboptimal.buffjson.proto.NestedMessage.newBuilder().setValue(1))
				.addRepeatedNested(io.suboptimal.buffjson.proto.NestedMessage.newBuilder().setName("x")).build();
		byte[] json = JsonFormat.printer().omittingInsignificantWhitespace().print(original)
				.getBytes(StandardCharsets.UTF_8);
		FastInput in = new FastInput(json, 0, json.length);
		Message decoded = decoder.readFast(in);
		assertTrue(in.finished());
		assertSame(message, decoded.getClass());
		assertEquals(original.toString(), decoded.toString());
	}

	/**
	 * A class loaded by a fresh loader has never been seen, so every thread that
	 * asks for its reader at once is a first use.
	 */
	@Test
	void threadsRacingForTheFirstUseShareOneReader() throws Exception {
		Assumptions.assumeTrue(RuntimeCodegen.available(), "needs the Class-File API (Java 24+)");
		Class<?> message = new IsolatingLoader(getClass().getClassLoader()).loadClass(TestNesting.class.getName());
		int threads = 8;
		CountDownLatch go = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			List<Future<Object>> readers = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				boolean useCompiled = i % 2 == 0;
				readers.add(pool.submit(() -> {
					go.await();
					return RuntimeCodegen.fastDecoder(message, useCompiled);
				}));
			}
			go.countDown();
			Object even = readers.get(0).get(2, TimeUnit.MINUTES);
			Object odd = readers.get(1).get(2, TimeUnit.MINUTES);
			assertNotNull(even, () -> String.valueOf(RuntimeCodegen.failure(message, true)));
			assertNotNull(odd, () -> String.valueOf(RuntimeCodegen.failure(message, false)));
			for (int i = 0; i < threads; i++) {
				assertSame(i % 2 == 0 ? even : odd, readers.get(i).get(2, TimeUnit.MINUTES), "thread " + i);
			}
		} finally {
			pool.shutdownNow();
		}
	}
}
