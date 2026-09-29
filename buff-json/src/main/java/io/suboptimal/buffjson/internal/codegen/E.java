package io.suboptimal.buffjson.internal.codegen;

import java.util.ArrayList;
import java.util.List;

import io.suboptimal.buffjson.internal.codegen.Expr.Op;

/** Factory methods for {@link Expr} nodes; import statically in templates. */
public final class E {

	private E() {
	}

	// --- literals ---

	public static Expr i(int value) {
		return new Expr.IntLit(value, Expr.Style.DECIMAL);
	}

	/** An {@code int} written in hexadecimal. */
	public static Expr hex(int value) {
		return new Expr.IntLit(value, Expr.Style.HEX);
	}

	/**
	 * A character constant; it has type {@code int}, like a promoted {@code char}.
	 */
	public static Expr chr(char value) {
		return new Expr.IntLit(value, Expr.Style.CHAR);
	}

	public static Expr l(long value) {
		return new Expr.LongLit(value, Expr.Style.DECIMAL);
	}

	public static Expr hex(long value) {
		return new Expr.LongLit(value, Expr.Style.HEX);
	}

	public static Expr bool(boolean value) {
		return new Expr.BoolLit(value);
	}

	public static Expr str(String value) {
		return new Expr.StrLit(value);
	}

	public static Expr nul(Ty type) {
		return new Expr.NullLit(type);
	}

	// --- operators ---

	public static Expr at(Expr array, Expr index) {
		return new Expr.ArrayLoad(array, index);
	}

	public static Expr bin(Op op, Expr left, Expr right) {
		return new Expr.Binary(op, left, right);
	}

	public static Expr add(Expr a, Expr b) {
		return bin(Op.ADD, a, b);
	}

	public static Expr add(Expr a, int b) {
		return bin(Op.ADD, a, i(b));
	}

	public static Expr sub(Expr a, Expr b) {
		return bin(Op.SUB, a, b);
	}

	public static Expr and(Expr a, Expr b) {
		return bin(Op.AND, a, b);
	}

	public static Expr and(Expr a, int b) {
		return bin(Op.AND, a, i(b));
	}

	public static Expr or(Expr a, Expr b) {
		return bin(Op.OR, a, b);
	}

	public static Expr shl(Expr a, int b) {
		return bin(Op.SHL, a, i(b));
	}

	public static Expr shr(Expr a, int b) {
		return bin(Op.SHR, a, i(b));
	}

	public static Expr ushr(Expr a, int b) {
		return bin(Op.USHR, a, i(b));
	}

	public static Expr mul(Expr a, Expr b) {
		return bin(Op.MUL, a, b);
	}

	public static Expr eq(Expr a, Expr b) {
		return bin(Op.EQ, a, b);
	}

	public static Expr eq(Expr a, int b) {
		return bin(Op.EQ, a, i(b));
	}

	public static Expr ne(Expr a, Expr b) {
		return bin(Op.NE, a, b);
	}

	public static Expr ne(Expr a, int b) {
		return bin(Op.NE, a, i(b));
	}

	public static Expr lt(Expr a, Expr b) {
		return bin(Op.LT, a, b);
	}

	public static Expr lt(Expr a, int b) {
		return bin(Op.LT, a, i(b));
	}

	public static Expr le(Expr a, Expr b) {
		return bin(Op.LE, a, b);
	}

	public static Expr ge(Expr a, int b) {
		return bin(Op.GE, a, i(b));
	}

	public static Expr land(Expr a, Expr b) {
		return bin(Op.LAND, a, b);
	}

	public static Expr not(Expr a) {
		return new Expr.Not(a);
	}

	public static Expr cast(Ty to, Expr operand) {
		return new Expr.Cast(to, operand);
	}

	// --- calls and fields ---

	/**
	 * A static call. The declared parameter types are taken to be the static types
	 * of the arguments, which is right for every call the templates make.
	 */
	public static Expr callStatic(Ty.Obj owner, String name, Ty ret, Expr... args) {
		return new Expr.Call(Expr.Kind.STATIC, owner, name, typesOf(args), ret, null, List.of(args));
	}

	/**
	 * An instance call on a class; see {@link #callStatic} for the parameter types.
	 */
	public static Expr call(Expr receiver, Ty.Obj owner, String name, Ty ret, Expr... args) {
		return new Expr.Call(Expr.Kind.VIRTUAL, owner, name, typesOf(args), ret, receiver, List.of(args));
	}

	/** An instance call whose parameter types differ from the argument types. */
	public static Expr call(Expr receiver, Ty.Obj owner, String name, Ty ret, List<Ty> params, Expr... args) {
		return new Expr.Call(Expr.Kind.VIRTUAL, owner, name, params, ret, receiver, List.of(args));
	}

	/** An interface call. */
	public static Expr callInterface(Expr receiver, Ty.Obj owner, String name, Ty ret, Expr... args) {
		return new Expr.Call(Expr.Kind.INTERFACE, owner, name, typesOf(args), ret, receiver, List.of(args));
	}

	public static Expr callStatic(Ty.Obj owner, String name, Ty ret, List<Ty> params, Expr... args) {
		return new Expr.Call(Expr.Kind.STATIC, owner, name, params, ret, null, List.of(args));
	}

	public static Expr newObj(Ty.Obj type, Expr... args) {
		return new Expr.New(type, typesOf(args), List.of(args));
	}

	public static Expr classLit(Ty.Obj type) {
		return new Expr.ClassLit(type);
	}

	public static Expr getStatic(Ty.Obj owner, String name, Ty type) {
		return new Expr.StaticGet(owner, name, type);
	}

	private static List<Ty> typesOf(Expr[] args) {
		List<Ty> types = new ArrayList<>(args.length);
		for (Expr arg : args) {
			types.add(arg.type());
		}
		return types;
	}
}
