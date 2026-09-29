package io.suboptimal.buffjson.internal.codegen;

/**
 * A local variable or parameter. Identity matters: two variables with the same
 * name are different variables, and a backend gives each its own slot.
 */
public final class Var implements Expr {

	private final String name;
	private final Ty type;

	public Var(String name, Ty type) {
		this.name = name;
		this.type = type;
	}

	public String name() {
		return name;
	}

	@Override
	public Ty type() {
		return type;
	}

	@Override
	public String toString() {
		return name;
	}
}
