package io.suboptimal.buffjson.internal.codegen;

import java.util.List;

/**
 * The members of a generated class: what a template produces, what the source
 * printer writes into a class body, and what the class-file backend lowers.
 */
public record Members(List<FieldDef> fields, List<MethodDef> methods) {

	/**
	 * A field. Static fields carry their initialiser ({@code init}); it runs in the
	 * class initialiser, in declaration order.
	 *
	 * @param access
	 *            {@link java.lang.reflect.Modifier} bits
	 */
	public record FieldDef(int access, Ty type, String name, Expr init) {
	}

	/**
	 * A method.
	 *
	 * @param access
	 *            {@link java.lang.reflect.Modifier} bits
	 * @param override
	 *            the method implements an interface method: printed with
	 *            {@code @Override}
	 * @param bridge
	 *            when non-null, the erased return type of the interface method this
	 *            one implements covariantly. javac creates that bridge itself; the
	 *            class-file backend has to write it
	 */
	public record MethodDef(int access, Ty ret, String name, List<Var> params, List<Stmt> body, boolean override,
			Ty bridge) {
	}
}
