package io.suboptimal.buffjson.internal.codegen;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Builds a list of statements. Templates describe code by calling these methods
 * (nested scopes are lambdas), which reads close to the Java being produced.
 */
public final class Block {

	private final List<Stmt> statements = new ArrayList<>();

	public List<Stmt> statements() {
		return statements;
	}

	/** Declares {@code name} initialised to {@code init}. */
	public Var decl(Ty type, String name, Expr init) {
		Var var = new Var(name, type);
		statements.add(new Stmt.Decl(var, init));
		return var;
	}

	/**
	 * Declares {@code name} without a value; it must be assigned on every path
	 * before use.
	 */
	public Var decl(Ty type, String name) {
		return decl(type, name, null);
	}

	public Block assign(Var var, Expr value) {
		statements.add(new Stmt.Assign(var, value));
		return this;
	}

	/** Evaluates {@code call} and discards any result. */
	public Block exec(Expr call) {
		statements.add(new Stmt.Exec(call));
		return this;
	}

	public Block when(Expr condition, Consumer<Block> then) {
		return when(condition, then, null);
	}

	public Block when(Expr condition, Consumer<Block> then, Consumer<Block> otherwise) {
		statements.add(new Stmt.If(condition, scope(then), otherwise == null ? List.of() : scope(otherwise)));
		return this;
	}

	/** {@code while (true) body}. */
	public Block loop(Consumer<Block> body) {
		return loop(E.bool(true), body);
	}

	public Block loop(Expr condition, Consumer<Block> body) {
		statements.add(new Stmt.While(condition, scope(body)));
		return this;
	}

	public Block switchOn(Expr selector, Consumer<Switcher> cases) {
		Switcher switcher = new Switcher();
		cases.accept(switcher);
		statements.add(new Stmt.Switch(selector, switcher.cases, switcher.defaultBody));
		return this;
	}

	public Block brk() {
		statements.add(new Stmt.Break());
		return this;
	}

	public Block ret(Expr value) {
		statements.add(new Stmt.Return(value));
		return this;
	}

	public Block ret() {
		statements.add(new Stmt.Return(null));
		return this;
	}

	public Block thr(Expr value) {
		statements.add(new Stmt.Throw(value));
		return this;
	}

	/** A nested scope, so variables declared inside do not leak into the rest. */
	public Block nested(Consumer<Block> body) {
		statements.add(new Stmt.Block(scope(body)));
		return this;
	}

	private static List<Stmt> scope(Consumer<Block> body) {
		Block inner = new Block();
		body.accept(inner);
		return inner.statements;
	}

	/** Collects the cases of a switch. */
	public static final class Switcher {
		private final List<Stmt.Case> cases = new ArrayList<>();
		private List<Stmt> defaultBody;

		public Switcher on(int label, Consumer<Block> body) {
			cases.add(new Stmt.Case(List.of(label), scope(body)));
			return this;
		}

		public Switcher otherwise(Consumer<Block> body) {
			defaultBody = scope(body);
			return this;
		}
	}
}
