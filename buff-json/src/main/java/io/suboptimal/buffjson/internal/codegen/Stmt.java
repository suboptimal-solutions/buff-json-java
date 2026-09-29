package io.suboptimal.buffjson.internal.codegen;

import java.util.List;

/**
 * A statement of the code model: structured control flow only (no labels or
 * gotos), which is what both Java source and class-file output can express.
 */
public sealed interface Stmt {

	/** Declares a local, optionally initialised. */
	record Decl(Var var, Expr init) implements Stmt {
	}

	record Assign(Var var, Expr value) implements Stmt {
	}

	/** Evaluates a call for its effect; a returned value is discarded. */
	record Exec(Expr call) implements Stmt {
	}

	record If(Expr condition, List<Stmt> then, List<Stmt> otherwise) implements Stmt {
	}

	/**
	 * {@code while (condition)}; {@code while (true)} for an endless loop left by
	 * {@link Break}.
	 */
	record While(Expr condition, List<Stmt> body) implements Stmt {
	}

	/**
	 * A switch over an {@code int}. Cases do not fall through; {@link Break} leaves
	 * the switch. {@code defaultBody} is {@code null} when there is no default.
	 */
	record Switch(Expr selector, List<Case> cases, List<Stmt> defaultBody) implements Stmt {
	}

	record Case(List<Integer> labels, List<Stmt> body) {
	}

	/** Leaves the innermost loop or switch. */
	record Break() implements Stmt {
	}

	/** {@code value} is {@code null} for a {@code void} method. */
	record Return(Expr value) implements Stmt {
	}

	record Throw(Expr value) implements Stmt {
	}

	/** A nested scope. */
	record Block(List<Stmt> body) implements Stmt {
	}
}
