package io.suboptimal.buffjson;

import static io.suboptimal.buffjson.internal.codegen.E.*;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import io.suboptimal.buffjson.internal.codegen.Block;
import io.suboptimal.buffjson.internal.codegen.ClassDef;
import io.suboptimal.buffjson.internal.codegen.Expr;
import io.suboptimal.buffjson.internal.codegen.Expr.Op;
import io.suboptimal.buffjson.internal.codegen.Members;
import io.suboptimal.buffjson.internal.codegen.Members.FieldDef;
import io.suboptimal.buffjson.internal.codegen.Members.MethodDef;
import io.suboptimal.buffjson.internal.codegen.RuntimeCodegen;
import io.suboptimal.buffjson.internal.codegen.SourcePrinter;
import io.suboptimal.buffjson.internal.codegen.Ty;
import io.suboptimal.buffjson.internal.codegen.Var;

/**
 * The code model the readers are described in: how it prints as Java (used by
 * the protoc plugin) and, on Java 24+, that lowering it to bytecode computes
 * what the same Java computes (used at run time). The generated readers are
 * checked against the plugin's and the general decoder elsewhere; this pins the
 * two backends on small programs, so a defect in one of them shows up as a
 * small failure instead of as a mismatch on some document.
 */
class CodegenModelTest {

	// ------------------------------------------------------------- printing

	private static Var v(String name, Ty type) {
		return new Var(name, type);
	}

	@Test
	void expressionsPrintWithTheParenthesesJavaNeedsAndSomeItDoesNot() {
		Var a = v("a", Ty.INT), b = v("b", Ty.INT), c = v("c", Ty.INT), r = v("r", Ty.LONG);
		assertEquals("(a + b) * c", SourcePrinter.print(mul(add(a, b), c)));
		assertEquals("a - (b - c)", SourcePrinter.print(sub(a, sub(b, c))));
		assertEquals("a - b - c", SourcePrinter.print(sub(sub(a, b), c)));
		assertEquals("(int) (r >> 32)", SourcePrinter.print(cast(Ty.INT, shr(r, 32))));
		assertEquals("((long) a << 32) | (b + 1)", SourcePrinter.print(or(shl(cast(Ty.LONG, a), 32), add(b, 1))));
		assertEquals("(a & 63) == 0", SourcePrinter.print(eq(and(a, 63), 0)));
		assertEquals("(a >> 6) == 3", SourcePrinter.print(eq(shr(a, 6), 3)));
		assertEquals("a + 4 == b", SourcePrinter.print(eq(add(a, 4), b)));
		assertEquals("!(a == b)", SourcePrinter.print(not(eq(a, b))));
		assertEquals("a == b && b != c", SourcePrinter.print(land(eq(a, b), ne(b, c))));
		// a cast to the type an expression already has is not written (javac
		// -Xlint:cast)
		assertEquals("a", SourcePrinter.print(cast(Ty.INT, a)));
	}

	@Test
	void literalsPrintInTheirRequestedSpelling() {
		assertEquals("'{'", SourcePrinter.print(chr('{')));
		assertEquals("'\\''", SourcePrinter.print(chr('\'')));
		assertEquals("'\\\\'", SourcePrinter.print(chr('\\')));
		assertEquals("10", SourcePrinter.print(chr('\n')));
		assertEquals("0x9e3779b1", SourcePrinter.print(hex(0x9E3779B1)));
		assertEquals("0x73654e6465746165L", SourcePrinter.print(hex(0x73654e6465746165L)));
		assertEquals("-1", SourcePrinter.print(i(-1)));
		assertEquals("7L", SourcePrinter.print(l(7)));
		assertEquals("\"a\\\"b\\n\\u00e9\"", SourcePrinter.print(str("a\"b\né")));
		assertEquals("java.lang.String.valueOf(1)",
				SourcePrinter.print(callStatic(Ty.STRING, "valueOf", Ty.STRING, i(1))));
		assertEquals("new java.lang.String(bytes, 1, 2)",
				SourcePrinter.print(newObj(Ty.STRING, v("bytes", Ty.BYTES), i(1), i(2))));
		assertEquals("java.nio.charset.StandardCharsets.UTF_8",
				SourcePrinter.print(getStatic(Ty.obj("java.nio.charset.StandardCharsets"), "UTF_8", Ty.OBJECT)));
	}

	@Test
	void nestedClassesPrintWithDotsAndGeneratedNamesKeepTheirDollars() {
		assertEquals("pkg.Outer.Inner.Builder", Ty.obj("pkg.Outer$Inner$Builder").source());
		assertEquals("pkg.Outer$$Generated", Ty.obj("pkg.Outer$$Generated").source());
		assertEquals("byte[][]", new Ty.Arr(Ty.BYTES).source());
		assertEquals("[[B", new Ty.Arr(Ty.BYTES).descriptor());
	}

	@Test
	void statementsPrintAsJava() {
		Var p = v("p", Ty.INT), b = v("b", Ty.BYTES);
		Block body = new Block();
		body.when(ne(at(b, p), chr('{')), t -> t.thr(callStatic(Ty.obj("x.Bail"), "bail", Ty.OBJECT)));
		body.assign(p, add(p, 1));
		body.assign(p, add(p, and(p, 63)));
		Var f = body.decl(Ty.INT, "f");
		body.when(eq(p, 0), t -> t.assign(f, i(1)),
				e -> e.when(eq(p, 1), t -> t.assign(f, i(2)), x -> x.assign(f, i(3))));
		body.switchOn(f, sw -> sw.on(1, x -> x.assign(p, i(5))).on(0x12345678, x -> {
			x.assign(p, i(6));
			x.brk();
		}).otherwise(x -> {
		}));
		body.loop(w -> w.when(eq(p, 9), t -> t.brk()));
		body.ret(f);
		StringBuilder out = new StringBuilder();
		SourcePrinter.print(out, new Members(List.of(), List.of(new MethodDef(Modifier.PRIVATE | Modifier.STATIC,
				Ty.INT, "m", List.of(b, p), body.statements(), false, null))), "    ");
		assertEquals("""

				    private static int m(byte[] b, int p) {
				        if (b[p] != '{') throw x.Bail.bail();
				        p++;
				        p += p & 63;
				        int f;
				        if (p == 0) {
				            f = 1;
				        } else if (p == 1) {
				            f = 2;
				        } else {
				            f = 3;
				        }
				        switch (f) {
				            case 1 -> p = 5;
				            case 0x12345678 -> {
				                p = 6;
				                break;
				            }
				            default -> {
				            }
				        }
				        while (true) {
				            if (p == 9) break;
				        }
				        return f;
				    }
				""", out.toString());
	}

	// ------------------------------------------------------ lowering to bytecode

	private static int counter;

	/** Lowers the members to a class, defines it and returns it. */
	private static Class<?> define(List<FieldDef> fields, List<MethodDef> methods) throws Exception {
		Assumptions.assumeTrue(RuntimeCodegen.available(), "needs the Class-File API (Java 24+)");
		Ty.Obj name = Ty.obj("io.suboptimal.buffjson", "GeneratedByCodegenModelTest" + counter++);
		byte[] bytes = RuntimeCodegen.backend().lower(new ClassDef(name, List.of(), new Members(fields, methods)),
				CodegenModelTest.class.getClassLoader());
		return MethodHandles.lookup().defineClass(bytes);
	}

	private static MethodDef staticMethod(Ty ret, String name, List<Var> params, Block body) {
		return new MethodDef(Modifier.PUBLIC | Modifier.STATIC, ret, name, params, body.statements(), false, null);
	}

	private static Object invoke(Class<?> type, String name, Object... args) throws Exception {
		for (Method method : type.getDeclaredMethods()) {
			if (method.getName().equals(name)) {
				try {
					return method.invoke(null, args);
				} catch (InvocationTargetException e) {
					if (e.getCause() instanceof Exception cause) {
						throw cause;
					}
					throw e;
				}
			}
		}
		throw new NoSuchMethodException(name);
	}

	@Test
	void intArithmeticShiftsAndBitwiseOperatorsMatchJava() throws Exception {
		Var a = v("a", Ty.INT), b = v("b", Ty.INT);
		Block body = new Block();
		// (a + b) * 3 - (a & b) + (b >> 1) - (a >>> 28) + ((a | b) - (a ^ b))
		body.ret(add(sub(add(sub(add(mul(add(a, b), i(3)), i(0)), and(a, b)), shr(b, 1)), ushr(a, 28)),
				sub(or(a, b), bin(Op.XOR, a, b))));
		Var c = v("c", Ty.INT), d = v("d", Ty.INT);
		Block second = new Block();
		second.ret(add(shl(c, 2), sub(c, d)));
		Class<?> type = define(List.of(), List.of(staticMethod(Ty.INT, "f", List.of(a, b), body),
				staticMethod(Ty.INT, "g", List.of(c, d), second)));
		Random random = new Random(1);
		for (int n = 0; n < 2000; n++) {
			int x = random.nextInt(), y = random.nextInt();
			assertEquals((x + y) * 3 - (x & y) + (y >> 1) - (x >>> 28) + ((x | y) - (x ^ y)), invoke(type, "f", x, y));
			assertEquals((x << 2) + (x - y), invoke(type, "g", x, y));
		}
	}

	@Test
	void longArithmeticPromotionAndConversionsMatchJava() throws Exception {
		Var x = v("x", Ty.LONG), s = v("s", Ty.INT), e = v("e", Ty.INT);
		Block body = new Block();
		// ((long) s << 32) | (e + 1) and (int) (x >> 32) and x + s and x >>> s
		Var packed = body.decl(Ty.LONG, "packed", or(shl(cast(Ty.LONG, s), 32), add(e, 1)));
		Var high = body.decl(Ty.INT, "high", cast(Ty.INT, shr(x, 32)));
		body.ret(add(add(packed, high), bin(Op.USHR, x, s)));
		Var narrow = v("n", Ty.INT);
		Block second = new Block();
		second.ret(add(cast(Ty.BYTE, narrow), cast(Ty.INT, cast(Ty.LONG, narrow))));
		Class<?> type = define(List.of(), List.of(staticMethod(Ty.LONG, "f", List.of(x, s, e), body),
				staticMethod(Ty.INT, "narrow", List.of(narrow), second)));
		Random random = new Random(2);
		for (int n = 0; n < 2000; n++) {
			long xv = random.nextLong();
			int sv = random.nextInt(), ev = random.nextInt();
			long expected = (((long) sv << 32) | (ev + 1)) + (int) (xv >> 32) + (xv >>> sv);
			// Java's (long) s << 32 | (e + 1) sign-extends the int e + 1, as the model does
			assertEquals(expected, invoke(type, "f", xv, sv, ev));
			int nv = random.nextInt();
			assertEquals((byte) nv + nv, invoke(type, "narrow", nv));
		}
	}

	@Test
	void conditionsShortCircuitAndNegateLikeJava() throws Exception {
		Var a = v("a", Ty.INT), b = v("b", Ty.INT);
		Block body = new Block();
		Var result = body.decl(Ty.INT, "result", i(0));
		body.when(land(bin(Op.GT, a, b), bin(Op.LOR, bin(Op.LT, a, i(2)), not(eq(a, b)))),
				t -> t.assign(result, add(result, 1)));
		body.when(not(land(ne(a, b), bin(Op.LE, a, i(3)))), t -> t.assign(result, add(result, 10)));
		body.when(bin(Op.LOR, eq(a, i(7)), bin(Op.GE, b, i(7))), t -> t.assign(result, add(result, 100)));
		// a boolean used as a value
		Var flag = body.decl(Ty.BOOLEAN, "flag");
		body.when(bin(Op.GE, a, b), t -> t.assign(flag, bool(true)), e -> e.assign(flag, bool(false)));
		body.when(flag, t -> t.assign(result, add(result, 1000)));
		body.when(eq(bin(Op.LAND, bin(Op.GT, a, b), ne(b, i(0))), bool(true)),
				t -> t.assign(result, add(result, 10000)));
		body.ret(result);
		Class<?> type = define(List.of(), List.of(staticMethod(Ty.INT, "f", List.of(a, b), body)));
		for (int x = -3; x <= 9; x++) {
			for (int y = -3; y <= 9; y++) {
				int expected = 0;
				if (x > y && (x < 2 || !(x == y)))
					expected += 1;
				if (!(x != y && x <= 3))
					expected += 10;
				if (x == 7 || y >= 7)
					expected += 100;
				boolean flagValue = x >= y;
				if (flagValue)
					expected += 1000;
				if ((x > y && y != 0) == true)
					expected += 10000;
				assertEquals(expected, invoke(type, "f", x, y), x + "," + y);
			}
		}
	}

	@Test
	void longComparisonsMatchJava() throws Exception {
		Var a = v("a", Ty.LONG), b = v("b", Ty.LONG);
		Block body = new Block();
		Var result = body.decl(Ty.INT, "result", i(0));
		body.when(bin(Op.LT, a, b), t -> t.assign(result, add(result, 1)));
		body.when(eq(a, b), t -> t.assign(result, add(result, 2)));
		body.when(bin(Op.GE, a, i(0)), t -> t.assign(result, add(result, 4)));
		body.when(ne(a, l(5)), t -> t.assign(result, add(result, 8)));
		body.ret(result);
		Class<?> type = define(List.of(), List.of(staticMethod(Ty.INT, "f", List.of(a, b), body)));
		long[] values = {Long.MIN_VALUE, -1, 0, 1, 5, 6, Long.MAX_VALUE, 1L << 32, -(1L << 32)};
		for (long x : values) {
			for (long y : values) {
				int expected = (x < y ? 1 : 0) + (x == y ? 2 : 0) + (x >= 0 ? 4 : 0) + (x != 5 ? 8 : 0);
				assertEquals(expected, invoke(type, "f", x, y), x + "," + y);
			}
		}
	}

	private static Block switchBody(Var selector, int[] labels, boolean withDefault) {
		Block body = new Block();
		Var out = body.decl(Ty.INT, "out", i(-100));
		body.switchOn(selector, sw -> {
			for (int label : labels) {
				sw.on(label, x -> x.assign(out, i(label * 2 + 1)));
			}
			if (withDefault) {
				sw.otherwise(x -> x.assign(out, i(-1)));
			}
		});
		body.ret(out);
		return body;
	}

	private static int expectedSwitch(int selector, int[] labels, boolean withDefault) {
		for (int label : labels) {
			if (label == selector) {
				return label * 2 + 1;
			}
		}
		return withDefault ? -1 : -100;
	}

	@Test
	void switchesOfEveryShapeMatchJava() throws Exception {
		int[][] shapes = {{0, 1, 2, 3}, {1, 2, 4, 5, 7}, {-2, 0, 3}, {10, 1000, 100000},
				{0x73656e22, 0x70657222, 0x756e6522}, {Integer.MIN_VALUE, -1, 0, Integer.MAX_VALUE}, {5}, {}};
		for (int[] labels : shapes) {
			for (boolean withDefault : new boolean[]{true, false}) {
				Var selector = v("s", Ty.INT);
				Class<?> type = define(List.of(), List
						.of(staticMethod(Ty.INT, "f", List.of(selector), switchBody(selector, labels, withDefault))));
				int[] probes = new int[labels.length * 3 + 6];
				for (int i = 0; i < labels.length; i++) {
					probes[3 * i] = labels[i];
					probes[3 * i + 1] = labels[i] + 1;
					probes[3 * i + 2] = labels[i] - 1;
				}
				probes[probes.length - 1] = Integer.MAX_VALUE;
				probes[probes.length - 2] = Integer.MIN_VALUE;
				probes[probes.length - 3] = 0;
				probes[probes.length - 4] = -1;
				probes[probes.length - 5] = 1;
				probes[probes.length - 6] = 6;
				for (int probe : probes) {
					assertEquals(expectedSwitch(probe, labels, withDefault), invoke(type, "f", probe),
							java.util.Arrays.toString(labels) + " default=" + withDefault + " probe=" + probe);
				}
			}
		}
	}

	@Test
	void breakLeavesTheInnermostLoopOrSwitchOnly() throws Exception {
		// n = 0; while (true) { switch (n & 3) { case 0 -> { count += 1; break; } case
		// 1 -> count += 10; default -> count += 100; }
		// n++; if (n == limit) break; }
		Var limit = v("limit", Ty.INT);
		Block body = new Block();
		Var n = body.decl(Ty.INT, "n", i(0));
		Var count = body.decl(Ty.INT, "count", i(0));
		body.loop(w -> {
			w.switchOn(and(n, 3), sw -> sw.on(0, x -> {
				x.assign(count, add(count, 1));
				x.brk();
			}).on(1, x -> x.assign(count, add(count, 10))).otherwise(x -> x.assign(count, add(count, 100))));
			w.assign(n, add(n, 1));
			w.when(eq(n, limit), t -> t.brk());
		});
		body.ret(count);
		Class<?> type = define(List.of(), List.of(staticMethod(Ty.INT, "f", List.of(limit), body)));
		for (int lim = 1; lim < 30; lim++) {
			int expected = 0;
			for (int k = 0; k < lim; k++) {
				expected += (k & 3) == 0 ? 1 : (k & 3) == 1 ? 10 : 100;
			}
			assertEquals(expected, invoke(type, "f", lim), "limit " + lim);
		}
	}

	@Test
	void arraysStringsConstantsAndObjectsWork() throws Exception {
		Ty.Obj charsets = Ty.obj("java.nio.charset.StandardCharsets");
		Ty.Obj charset = Ty.obj("java.nio.charset.Charset");
		Var bytes = v("bytes", Ty.BYTES), at = v("at", Ty.INT);
		Block load = new Block();
		load.ret(at(bytes, at)); // a byte is sign-extended when it becomes an int
		Var text = v("text", Ty.STRING);
		Block hash = new Block();
		Var found = hash.decl(Ty.INT, "found", i(-1));
		hash.switchOn(call(text, Ty.STRING, "hashCode", Ty.INT), sw -> {
			sw.on("alpha".hashCode(),
					x -> x.when(call(text, Ty.STRING, "equals", Ty.BOOLEAN, List.of(Ty.OBJECT), str("alpha")),
							t -> t.assign(found, i(1))));
			sw.on("beta".hashCode(),
					x -> x.when(call(text, Ty.STRING, "equals", Ty.BOOLEAN, List.of(Ty.OBJECT), str("beta")),
							t -> t.assign(found, i(2))));
		});
		hash.ret(found);
		Var from = v("from", Ty.INT), len = v("len", Ty.INT);
		Block make = new Block();
		make.ret(newObj(Ty.STRING, bytes, from, len, getStatic(charsets, "ISO_8859_1", charset)));
		FieldDef constant = new FieldDef(Modifier.PUBLIC | Modifier.STATIC | Modifier.FINAL, Ty.BYTES, "BYTES",
				call(str("héllo"), Ty.STRING, "getBytes", Ty.BYTES, getStatic(charsets, "UTF_8", charset)));
		Block read = new Block();
		read.ret(getStatic(Ty.obj("io.suboptimal.buffjson.GeneratedByCodegenModelTest" + counter), "BYTES", Ty.BYTES));
		Class<?> type = define(List.of(constant),
				List.of(staticMethod(Ty.INT, "load", List.of(bytes, at), load),
						staticMethod(Ty.INT, "hash", List.of(text), hash),
						staticMethod(Ty.STRING, "make", List.of(bytes, from, len), make),
						staticMethod(Ty.BYTES, "constant", List.of(), read)));
		assertEquals(-56, invoke(type, "load", new byte[]{(byte) 200}, 0));
		assertEquals(65, invoke(type, "load", new byte[]{0, 65}, 1));
		assertEquals(1, invoke(type, "hash", "alpha"));
		assertEquals(2, invoke(type, "hash", "beta"));
		assertEquals(-1, invoke(type, "hash", "gamma"));
		assertEquals("Ã©l", invoke(type, "make", "héllo".getBytes(StandardCharsets.UTF_8), 1, 3));
		assertArrayEquals("héllo".getBytes(StandardCharsets.UTF_8), (byte[]) invoke(type, "constant"));
		assertThrows(ArrayIndexOutOfBoundsException.class, () -> invoke(type, "load", new byte[1], 1));
	}

	@Test
	void throwAndReturnEndTheirPathsAndCallsDiscardTheirResults() throws Exception {
		Var flag = v("flag", Ty.INT);
		Block body = new Block();
		body.when(eq(flag, 0), t -> t.thr(newObj(Ty.obj("java.lang.IllegalStateException"), str("zero"))));
		// the result of a call statement is dropped (a long takes two slots, an object
		// one)
		body.exec(callStatic(Ty.obj("java.lang.Long"), "parseLong", Ty.LONG, str("5")));
		body.exec(callStatic(Ty.obj("java.lang.Long"), "toString", Ty.STRING, l(5)));
		body.when(eq(flag, 1), t -> t.ret(i(11)));
		body.ret(i(22));
		Class<?> type = define(List.of(), List.of(staticMethod(Ty.INT, "f", List.of(flag), body)));
		assertEquals(11, invoke(type, "f", 1));
		assertEquals(22, invoke(type, "f", 2));
		IllegalStateException e = assertThrows(IllegalStateException.class, () -> invoke(type, "f", 0));
		assertEquals("zero", e.getMessage());
	}

	@Test
	void aMethodThatCanFallOffItsEndIsRefused() throws Exception {
		Assumptions.assumeTrue(RuntimeCodegen.available(), "needs the Class-File API (Java 24+)");
		Var flag = v("flag", Ty.INT);
		Block body = new Block();
		body.when(eq(flag, 0), t -> t.ret(i(1)));
		assertThrows(IllegalStateException.class,
				() -> define(List.of(), List.of(staticMethod(Ty.INT, "f", List.of(flag), body))));
	}

	@Test
	void anIntOperandOfALongOperationIsPromoted() throws Exception {
		Var x = v("x", Ty.LONG), y = v("y", Ty.INT);
		Block body = new Block();
		body.ret(add(x, y));
		Class<?> type = define(List.of(), List.of(staticMethod(Ty.LONG, "f", List.of(x, y), body)));
		assertEquals(Long.MAX_VALUE, invoke(type, "f", Long.MAX_VALUE - 5, 5));
		assertEquals(-3L + 2, invoke(type, "f", -3L, 2));
		assertEquals(1L << 40, invoke(type, "f", (1L << 40) + 5, -5));
	}

	// ------------------------------------------------- conditions, systematically

	private static final Op[] COMPARISONS = {Op.EQ, Op.NE, Op.LT, Op.LE, Op.GT, Op.GE};

	private static boolean compare(Op op, long x, long y) {
		return switch (op) {
			case EQ -> x == y;
			case NE -> x != y;
			case LT -> x < y;
			case LE -> x <= y;
			case GT -> x > y;
			case GE -> x >= y;
			default -> throw new IllegalArgumentException(op.symbol);
		};
	}

	@Test
	void everyComparisonOfIntsAndLongsAgainstVariablesAndConstantsMatchesJava() throws Exception {
		// operand forms: variable, zero (a single-operand branch for ints), other
		// constant
		List<MethodDef> methods = new java.util.ArrayList<>();
		record Case(String method, Op op, Ty type, int form) {
		}
		List<Case> cases = new java.util.ArrayList<>();
		for (Ty type : new Ty[]{Ty.INT, Ty.LONG}) {
			for (Op op : COMPARISONS) {
				for (int form = 0; form < 3; form++) {
					Var x = v("x", type), y = v("y", type);
					Expr right = switch (form) {
						case 0 -> y;
						case 1 -> i(0);
						default -> type == Ty.LONG ? l(2) : i(2);
					};
					Block body = new Block();
					body.when(bin(op, x, right), t -> t.ret(i(1)));
					body.ret(i(0));
					String name = "c" + methods.size();
					methods.add(staticMethod(Ty.INT, name, List.of(x, y), body));
					cases.add(new Case(name, op, type, form));
				}
			}
		}
		Class<?> type = define(List.of(), methods);
		long[] values = {-5, -1, 0, 1, 2, 3, 100};
		for (Case c : cases) {
			for (long x : values) {
				for (long y : values) {
					long right = c.form == 0 ? y : c.form == 1 ? 0 : 2;
					Object[] args = c.type == Ty.LONG ? new Object[]{x, y} : new Object[]{(int) x, (int) y};
					assertEquals(compare(c.op, x, right) ? 1 : 0, invoke(type, c.method, args),
							c.type.source() + " x " + c.op.symbol + " " + (c.form == 0 ? "y" : c.form == 1 ? "0" : "2")
									+ " with x=" + x + " y=" + y);
				}
			}
		}
	}

	private static Expr randomCondition(Random rng, int depth, Var a, Var b) {
		if (depth == 0 || rng.nextInt(4) == 0) {
			Expr left = rng.nextBoolean() ? a : b;
			Expr right = switch (rng.nextInt(3)) {
				case 0 -> a;
				case 1 -> b;
				default -> i(rng.nextInt(7) - 3);
			};
			return bin(COMPARISONS[rng.nextInt(COMPARISONS.length)], left, right);
		}
		return switch (rng.nextInt(3)) {
			case 0 -> not(randomCondition(rng, depth - 1, a, b));
			case 1 -> bin(Op.LAND, randomCondition(rng, depth - 1, a, b), randomCondition(rng, depth - 1, a, b));
			default -> bin(Op.LOR, randomCondition(rng, depth - 1, a, b), randomCondition(rng, depth - 1, a, b));
		};
	}

	private static long operand(Expr e, Var a, Var b, int x, int y) {
		if (e == a) {
			return x;
		}
		if (e == b) {
			return y;
		}
		return ((Expr.IntLit) e).value();
	}

	private static boolean evaluate(Expr e, Var a, Var b, int x, int y) {
		return switch (e) {
			case Expr.Not n -> !evaluate(n.operand(), a, b, x, y);
			case Expr.Binary bin when bin.op() == Op.LAND ->
				evaluate(bin.left(), a, b, x, y) && evaluate(bin.right(), a, b, x, y);
			case Expr.Binary bin when bin.op() == Op.LOR ->
				evaluate(bin.left(), a, b, x, y) || evaluate(bin.right(), a, b, x, y);
			case Expr.Binary bin ->
				compare(bin.op(), operand(bin.left(), a, b, x, y), operand(bin.right(), a, b, x, y));
			default -> throw new IllegalArgumentException(e.toString());
		};
	}

	@Test
	void nestedLogicalConditionsAreBranchesAndValuesJustLikeInJava() throws Exception {
		Var a = v("a", Ty.INT), b = v("b", Ty.INT);
		Random rng = new Random(42);
		List<Expr> conditions = new java.util.ArrayList<>();
		List<MethodDef> methods = new java.util.ArrayList<>();
		for (int n = 0; n < 150; n++) {
			Expr condition = randomCondition(rng, 4, a, b);
			conditions.add(condition);
			// as a branch condition
			Block branch = new Block();
			branch.when(condition, t -> t.ret(i(1)));
			branch.ret(i(0));
			methods.add(staticMethod(Ty.INT, "branch" + n, List.of(a, b), branch));
			// as a value, and negated
			Block value = new Block();
			Var flag = value.decl(Ty.BOOLEAN, "flag", condition);
			Var negated = value.decl(Ty.BOOLEAN, "negated", not(condition));
			Var result = value.decl(Ty.INT, "result", i(0));
			value.when(flag, t -> t.assign(result, add(result, 1)));
			value.when(negated, t -> t.assign(result, add(result, 2)));
			value.when(land(flag, negated), t -> t.assign(result, add(result, 4)));
			value.ret(result);
			methods.add(staticMethod(Ty.INT, "value" + n, List.of(a, b), value));
		}
		Class<?> type = define(List.of(), methods);
		for (int n = 0; n < conditions.size(); n++) {
			for (int x = -3; x <= 3; x++) {
				for (int y = -3; y <= 3; y++) {
					boolean expected = evaluate(conditions.get(n), a, b, x, y);
					String where = SourcePrinter.print(conditions.get(n)) + " with a=" + x + " b=" + y;
					assertEquals(expected ? 1 : 0, invoke(type, "branch" + n, x, y), where);
					assertEquals(expected ? 1 : 2, invoke(type, "value" + n, x, y), where);
				}
			}
		}
	}

	@Test
	void referencesAreComparedByIdentityAndAgainstNull() throws Exception {
		Var s = v("s", Ty.STRING), t = v("t", Ty.STRING);
		Block body = new Block();
		Var result = body.decl(Ty.INT, "result", i(0));
		body.when(eq(s, t), x -> x.assign(result, add(result, 1)));
		body.when(ne(s, t), x -> x.assign(result, add(result, 2)));
		body.when(eq(s, nul(Ty.STRING)), x -> x.assign(result, add(result, 4)));
		body.when(ne(s, nul(Ty.STRING)), x -> x.assign(result, add(result, 8)));
		body.when(land(ne(t, nul(Ty.STRING)), eq(s, t)), x -> x.assign(result, add(result, 16)));
		body.ret(result);
		Class<?> type = define(List.of(), List.of(staticMethod(Ty.INT, "f", List.of(s, t), body)));
		String one = new String("same"), other = new String("same");
		assertEquals(1 + 8 + 16, invoke(type, "f", one, one));
		assertEquals(2 + 8, invoke(type, "f", one, other));
		assertEquals(1 + 4, invoke(type, "f", null, null));
		assertEquals(2 + 4, invoke(type, "f", null, one));
		assertEquals(2 + 8, invoke(type, "f", one, null));
	}
}
