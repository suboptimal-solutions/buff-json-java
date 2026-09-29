package io.suboptimal.buffjson.internal.codegen;

import java.util.List;

/**
 * An expression of the code model. Every expression knows its static
 * {@link #type()}, so a backend never has to infer one: the printer emits Java
 * source and the class-file backend emits typed instructions from the same
 * tree.
 */
public sealed interface Expr permits Var, Expr.IntLit, Expr.LongLit, Expr.BoolLit, Expr.StrLit, Expr.NullLit,
		Expr.ArrayLoad, Expr.Binary, Expr.Not, Expr.Cast, Expr.Call, Expr.New, Expr.StaticGet, Expr.ClassLit {

	Ty type();

	/** How an integer literal is spelled in source; the value is the same. */
	enum Style {
		DECIMAL, HEX, CHAR
	}

	record IntLit(int value, Style style) implements Expr {
		@Override
		public Ty type() {
			return Ty.INT;
		}
	}

	record LongLit(long value, Style style) implements Expr {
		@Override
		public Ty type() {
			return Ty.LONG;
		}
	}

	record BoolLit(boolean value) implements Expr {
		@Override
		public Ty type() {
			return Ty.BOOLEAN;
		}
	}

	record StrLit(String value) implements Expr {
		@Override
		public Ty type() {
			return Ty.STRING;
		}
	}

	record NullLit(Ty type) implements Expr {
	}

	/** {@code array[index]}. */
	record ArrayLoad(Expr array, Expr index) implements Expr {
		@Override
		public Ty type() {
			return ((Ty.Arr) array.type()).elem();
		}
	}

	enum Op {
		ADD("+", 11), SUB("-", 11), MUL("*", 12), AND("&", 7), OR("|", 5), XOR("^", 6), SHL("<<", 10), SHR(">>",
				10), USHR(">>>", 10), EQ("==",
						8), NE("!=", 8), LT("<", 9), LE("<=", 9), GT(">", 9), GE(">=", 9), LAND("&&", 4), LOR("||", 3);

		public final String symbol;
		public final int precedence;

		Op(String symbol, int precedence) {
			this.symbol = symbol;
			this.precedence = precedence;
		}

		public boolean isComparison() {
			return this == EQ || this == NE || this == LT || this == LE || this == GT || this == GE;
		}

		public boolean isShift() {
			return this == SHL || this == SHR || this == USHR;
		}

		public boolean isLogical() {
			return this == LAND || this == LOR;
		}
	}

	/**
	 * A binary operation with Java's numeric promotion: {@code int} unless an
	 * operand is {@code long}; shifts have the promoted type of the left operand;
	 * comparisons and {@code &&}/{@code ||} are {@code boolean}.
	 */
	record Binary(Op op, Expr left, Expr right) implements Expr {
		@Override
		public Ty type() {
			if (op.isComparison() || op.isLogical()) {
				return Ty.BOOLEAN;
			}
			if (op.isShift()) {
				return left.type() == Ty.LONG ? Ty.LONG : Ty.INT;
			}
			return promote(left.type(), right.type());
		}
	}

	/** Java's binary numeric promotion for the types the model uses. */
	static Ty promote(Ty a, Ty b) {
		if (a == Ty.DOUBLE || b == Ty.DOUBLE) {
			return Ty.DOUBLE;
		}
		if (a == Ty.FLOAT || b == Ty.FLOAT) {
			return Ty.FLOAT;
		}
		if (a == Ty.LONG || b == Ty.LONG) {
			return Ty.LONG;
		}
		return Ty.INT;
	}

	/** Logical negation. */
	record Not(Expr operand) implements Expr {
		@Override
		public Ty type() {
			return Ty.BOOLEAN;
		}
	}

	/** A primitive conversion or a reference downcast. */
	record Cast(Ty to, Expr operand) implements Expr {
		@Override
		public Ty type() {
			return to;
		}
	}

	enum Kind {
		STATIC, VIRTUAL, INTERFACE
	}

	/**
	 * A method call. {@code params} and {@code ret} are the callee's declared
	 * signature (which is what the class file links against); the arguments are
	 * converted to the parameter types where Java would widen them.
	 */
	record Call(Kind kind, Ty.Obj owner, String name, List<Ty> params, Ty ret, Expr receiver,
			List<Expr> args) implements Expr {
		public Call {
			if (params.size() != args.size()) {
				throw new IllegalArgumentException(owner.source() + "." + name + ": " + params.size() + " parameters, "
						+ args.size() + " arguments");
			}
			if ((kind == Kind.STATIC) != (receiver == null)) {
				throw new IllegalArgumentException(owner.source() + "." + name + ": receiver/kind mismatch");
			}
		}

		@Override
		public Ty type() {
			return ret;
		}
	}

	/** {@code new type(args)}. */
	record New(Ty.Obj type, List<Ty> params, List<Expr> args) implements Expr {
	}

	/** A class literal, {@code Type.class}. */
	record ClassLit(Ty.Obj target) implements Expr {
		@Override
		public Ty type() {
			return Ty.obj("java.lang.Class");
		}
	}

	/** A static field read, {@code Owner.NAME}. */
	record StaticGet(Ty.Obj owner, String name, Ty type) implements Expr {
	}
}
