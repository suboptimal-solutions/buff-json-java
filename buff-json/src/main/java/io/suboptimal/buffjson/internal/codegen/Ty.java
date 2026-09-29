package io.suboptimal.buffjson.internal.codegen;

import java.util.Objects;

/**
 * A Java type as the code model sees it: a primitive, a class (by binary name),
 * or an array. It knows how it is spelled in source ({@link #source()}) and in
 * a JVM descriptor ({@link #descriptor()}), so one description of generated
 * code can be printed as Java or lowered to bytecode.
 */
public sealed interface Ty {

	/** The type as written in Java source; classes are fully qualified. */
	String source();

	/**
	 * The JVM field descriptor, e.g. {@code I}, {@code [B},
	 * {@code Ljava/lang/String;}.
	 */
	String descriptor();

	Ty BOOLEAN = Prim.BOOLEAN;
	Ty BYTE = Prim.BYTE;
	Ty CHAR = Prim.CHAR;
	Ty SHORT = Prim.SHORT;
	Ty INT = Prim.INT;
	Ty LONG = Prim.LONG;
	Ty FLOAT = Prim.FLOAT;
	Ty DOUBLE = Prim.DOUBLE;
	Ty VOID = Prim.VOID;

	Obj OBJECT = new Obj("java.lang.Object");
	Obj STRING = new Obj("java.lang.String");
	Arr BYTES = new Arr(BYTE);

	/** {@code true} for the eight primitive types and {@code void}. */
	default boolean isPrimitive() {
		return this instanceof Prim;
	}

	/**
	 * {@code true} for {@code long} and {@code double}, which take two stack/local
	 * slots.
	 */
	default boolean isWide() {
		return this == Prim.LONG || this == Prim.DOUBLE;
	}

	/** The type of a loaded class. */
	static Ty of(Class<?> type) {
		if (type.isArray()) {
			return new Arr(of(type.getComponentType()));
		}
		if (type.isPrimitive()) {
			return switch (type.getName()) {
				case "boolean" -> BOOLEAN;
				case "byte" -> BYTE;
				case "char" -> CHAR;
				case "short" -> SHORT;
				case "int" -> INT;
				case "long" -> LONG;
				case "float" -> FLOAT;
				case "double" -> DOUBLE;
				default -> VOID;
			};
		}
		return new Obj(type.getName());
	}

	/** The class type named {@code binaryName} (nested classes use {@code $}). */
	static Obj obj(String binaryName) {
		return new Obj(binaryName);
	}

	/** The class {@code simpleBinaryName} in {@code packageName} (may be empty). */
	static Obj obj(String packageName, String simpleBinaryName) {
		return new Obj(packageName.isEmpty() ? simpleBinaryName : packageName + "." + simpleBinaryName);
	}

	enum Prim implements Ty {
		BOOLEAN("boolean", "Z"), BYTE("byte", "B"), CHAR("char", "C"), SHORT("short", "S"), INT("int",
				"I"), LONG("long", "J"), FLOAT("float", "F"), DOUBLE("double", "D"), VOID("void", "V");

		private final String source;
		private final String descriptor;

		Prim(String source, String descriptor) {
			this.source = source;
			this.descriptor = descriptor;
		}

		@Override
		public String source() {
			return source;
		}

		@Override
		public String descriptor() {
			return descriptor;
		}
	}

	/**
	 * A class or interface, named as {@code Class.getName()} would:
	 * {@code pkg.Outer$Inner}.
	 */
	record Obj(String binaryName) implements Ty {
		public Obj {
			Objects.requireNonNull(binaryName);
		}

		@Override
		public String source() {
			// Nested classes use '$' in binary names and '.' in source. A '$' that is part
			// of
			// a name proper (adjacent to another '$', as in classes generated at run time)
			// is kept.
			StringBuilder out = new StringBuilder(binaryName.length());
			for (int i = 0; i < binaryName.length(); i++) {
				char c = binaryName.charAt(i);
				boolean nesting = c == '$' && i > 0 && i + 1 < binaryName.length() && binaryName.charAt(i - 1) != '$'
						&& binaryName.charAt(i + 1) != '$';
				out.append(nesting ? '.' : c);
			}
			return out.toString();
		}

		@Override
		public String descriptor() {
			return "L" + internalName() + ";";
		}

		/** {@code pkg/Outer$Inner}, the form class files use. */
		public String internalName() {
			return binaryName.replace('.', '/');
		}

		public String packageName() {
			int dot = binaryName.lastIndexOf('.');
			return dot < 0 ? "" : binaryName.substring(0, dot);
		}
	}

	record Arr(Ty elem) implements Ty {
		public Arr {
			Objects.requireNonNull(elem);
		}

		@Override
		public String source() {
			return elem.source() + "[]";
		}

		@Override
		public String descriptor() {
			return "[" + elem.descriptor();
		}
	}
}
