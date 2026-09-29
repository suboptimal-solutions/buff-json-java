package io.suboptimal.buffjson.internal.codegen;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.stream.Collectors;

import io.suboptimal.buffjson.internal.codegen.Members.FieldDef;
import io.suboptimal.buffjson.internal.codegen.Members.MethodDef;

/**
 * Writes the code model as Java source: what the protoc plugin embeds in the
 * decoder classes it generates. Classes are always written fully qualified, so
 * the output does not depend on the imports of the class it is placed in (nor
 * can a message named {@code String} capture {@code java.lang.String}).
 */
public final class SourcePrinter {

	private final StringBuilder out;
	private String indent;

	private SourcePrinter(StringBuilder out, String indent) {
		this.out = out;
		this.indent = indent;
	}

	/**
	 * Appends the members to a class body whose members are indented by
	 * {@code indent}.
	 */
	public static void print(StringBuilder out, Members members, String indent) {
		SourcePrinter printer = new SourcePrinter(out, indent);
		for (FieldDef field : members.fields()) {
			printer.field(field);
		}
		for (MethodDef method : members.methods()) {
			printer.blank();
			printer.method(method);
		}
	}

	/** The source text of one expression (used by tests and diagnostics). */
	public static String print(Expr expression) {
		return new SourcePrinter(new StringBuilder(), "").expr(expression, 0);
	}

	// --- members ---

	private void field(FieldDef field) {
		String init = field.init() == null ? "" : " = " + expr(field.init(), 0);
		line(modifiers(field.access()) + field.type().source() + " " + field.name() + init + ";");
	}

	private void method(MethodDef method) {
		if (method.override()) {
			line("@Override");
		}
		String params = method.params().stream().map(p -> p.type().source() + " " + p.name())
				.collect(Collectors.joining(", "));
		open(modifiers(method.access()) + method.ret().source() + " " + method.name() + "(" + params + ") {");
		statements(method.body());
		close("}");
	}

	private static String modifiers(int access) {
		String text = Modifier
				.toString(access & (Modifier.PUBLIC | Modifier.PRIVATE | Modifier.STATIC | Modifier.FINAL));
		return text.isEmpty() ? "" : text + " ";
	}

	// --- statements ---

	private void statements(List<Stmt> body) {
		for (Stmt statement : body) {
			statement(statement);
		}
	}

	private void statement(Stmt statement) {
		switch (statement) {
			case Stmt.If s -> ifStatement(s, "");
			case Stmt.While s -> {
				open("while (" + expr(s.condition(), 0) + ") {");
				statements(s.body());
				close("}");
			}
			case Stmt.Switch s -> switchStatement(s);
			case Stmt.Block s -> {
				open("{");
				statements(s.body());
				close("}");
			}
			default -> line(simple(statement));
		}
	}

	/** One-line form of a statement that has one, else {@code null}. */
	private String inline(Stmt statement) {
		return switch (statement) {
			case Stmt.Decl s -> null;
			case Stmt.Assign s -> simple(s);
			case Stmt.Exec s -> simple(s);
			case Stmt.Return s -> simple(s);
			case Stmt.Throw s -> simple(s);
			case Stmt.Break s -> simple(s);
			default -> null;
		};
	}

	private String simple(Stmt statement) {
		return switch (statement) {
			case Stmt.Decl s -> {
				String init = s.init() == null ? "" : " = " + expr(s.init(), 0);
				yield s.var().type().source() + " " + s.var().name() + init + ";";
			}
			case Stmt.Assign s -> assignment(s);
			case Stmt.Exec s -> expr(s.call(), 0) + ";";
			case Stmt.Return s -> s.value() == null ? "return;" : "return " + expr(s.value(), 0) + ";";
			case Stmt.Throw s -> "throw " + expr(s.value(), 0) + ";";
			case Stmt.Break s -> "break;";
			default -> throw new IllegalStateException("not a simple statement: " + statement);
		};
	}

	/** {@code x = x + n} is written {@code x += n} (and {@code x++} for 1). */
	private String assignment(Stmt.Assign s) {
		String name = s.var().name();
		if (s.value() instanceof Expr.Binary b && b.left() == s.var()
				&& (b.op() == Expr.Op.ADD || b.op() == Expr.Op.SUB || b.op() == Expr.Op.AND || b.op() == Expr.Op.OR)
				&& s.var().type() == Ty.INT && b.type() == Ty.INT) {
			if (b.op() == Expr.Op.ADD && b.right() instanceof Expr.IntLit lit && lit.value() == 1) {
				return name + "++;";
			}
			return name + " " + b.op().symbol + "= " + expr(b.right(), 0) + ";";
		}
		return name + " = " + expr(s.value(), 0) + ";";
	}

	private void ifStatement(Stmt.If s, String prefix) {
		String head = prefix + "if (" + expr(s.condition(), 0) + ")";
		String single = s.then().size() == 1 ? inline(s.then().get(0)) : null;
		if (single != null && s.otherwise().isEmpty() && prefix.isEmpty()) {
			line(head + " " + single);
			return;
		}
		open(head + " {");
		statements(s.then());
		if (s.otherwise().isEmpty()) {
			close("}");
		} else if (s.otherwise().size() == 1 && s.otherwise().get(0) instanceof Stmt.If chained) {
			mid();
			ifStatement(chained, "} else ");
		} else {
			mid("} else {");
			statements(s.otherwise());
			close("}");
		}
	}

	private void switchStatement(Stmt.Switch s) {
		open("switch (" + expr(s.selector(), 0) + ") {");
		for (Stmt.Case c : s.cases()) {
			String labels = c.labels().stream().map(SourcePrinter::label).collect(Collectors.joining(", "));
			caseBody("case " + labels + " ->", c.body());
		}
		if (s.defaultBody() != null) {
			caseBody("default ->", s.defaultBody());
		}
		close("}");
	}

	private void caseBody(String head, List<Stmt> body) {
		String single = body.size() == 1 ? inline(body.get(0)) : null;
		if (single != null && !(body.get(0) instanceof Stmt.Return) && !(body.get(0) instanceof Stmt.Break)) {
			line(head + " " + single);
			return;
		}
		open(head + " {");
		statements(body);
		close("}");
	}

	private static String label(int value) {
		return value >= 0 && value < 0x10000 ? Integer.toString(value) : String.format("0x%08x", value);
	}

	// --- expressions ---

	private static final int UNARY = 14;
	private static final int PRIMARY = 16;

	private String expr(Expr expression, int minimum) {
		String text = switch (expression) {
			case Var v -> v.name();
			case Expr.IntLit lit -> intLiteral(lit);
			case Expr.LongLit lit -> longLiteral(lit);
			case Expr.BoolLit lit -> Boolean.toString(lit.value());
			case Expr.StrLit lit -> javaString(lit.value());
			case Expr.NullLit lit -> lit.type().isPrimitive() ? "null" : "(" + lit.type().source() + ") null";
			case Expr.ArrayLoad load -> expr(load.array(), PRIMARY) + "[" + expr(load.index(), 0) + "]";
			case Expr.Binary b -> operand(b, b.left(), false) + " " + b.op().symbol + " " + operand(b, b.right(), true);
			case Expr.Not n -> "!" + expr(n.operand(), UNARY);
			case Expr.Cast c -> c.operand().type().equals(c.to())
					? expr(c.operand(), minimum)
					: "(" + c.to().source() + ") " + expr(c.operand(), UNARY);
			case Expr.Call c -> call(c);
			case Expr.New n -> "new " + n.type().source() + "(" + args(n.args()) + ")";
			case Expr.StaticGet g -> g.owner().source() + "." + g.name();
			case Expr.ClassLit c -> c.target().source() + ".class";
		};
		return precedence(expression) < minimum ? "(" + text + ")" : text;
	}

	/**
	 * An operand of a binary operation: parenthesised where Java's precedence needs
	 * it, and also where it would not but a reader could take it the wrong way (a
	 * shift or a bitwise operation mixed with another kind of operator).
	 */
	private String operand(Expr.Binary parent, Expr operand, boolean right) {
		int minimum = parent.op().precedence + (right ? 1 : 0);
		String text = expr(operand, minimum);
		if (precedence(operand) >= minimum && operand instanceof Expr.Binary child && child.op() != parent.op()
				&& (bitwise(parent.op()) || bitwise(child.op())) && !parent.op().isLogical()) {
			return "(" + text + ")";
		}
		return text;
	}

	private static boolean bitwise(Expr.Op op) {
		return switch (op) {
			case AND, OR, XOR, SHL, SHR, USHR -> true;
			default -> false;
		};
	}

	private String call(Expr.Call c) {
		String target = c.kind() == Expr.Kind.STATIC ? c.owner().source() : expr(c.receiver(), PRIMARY);
		return target + "." + c.name() + "(" + args(c.args()) + ")";
	}

	private String args(List<Expr> args) {
		return args.stream().map(a -> expr(a, 0)).collect(Collectors.joining(", "));
	}

	private static int precedence(Expr e) {
		return switch (e) {
			case Expr.Binary b -> b.op().precedence;
			case Expr.Cast c -> c.operand().type().equals(c.to()) ? precedence(c.operand()) : UNARY;
			case Expr.Not n -> UNARY;
			case Expr.IntLit lit -> lit.value() < 0 && lit.style() == Expr.Style.DECIMAL ? UNARY : PRIMARY;
			case Expr.LongLit lit -> lit.value() < 0 && lit.style() == Expr.Style.DECIMAL ? UNARY : PRIMARY;
			default -> PRIMARY;
		};
	}

	private static String intLiteral(Expr.IntLit lit) {
		int v = lit.value();
		return switch (lit.style()) {
			case HEX -> String.format("0x%08x", v);
			case CHAR -> v >= 0x20 && v <= 0x7e
					? "'" + (v == '\'' || v == '\\' ? "\\" : "") + (char) v + "'"
					: Integer.toString(v);
			case DECIMAL -> Integer.toString(v);
		};
	}

	private static String longLiteral(Expr.LongLit lit) {
		return lit.style() == Expr.Style.HEX ? String.format("0x%016xL", lit.value()) : lit.value() + "L";
	}

	/**
	 * Quotes a Java string literal. LF and CR must use ordinary escapes: Java
	 * processes Unicode escapes before tokenizing, so Unicode-escaped line breaks
	 * would leave the string literal unclosed.
	 */
	public static String javaString(String value) {
		StringBuilder sb = new StringBuilder(value.length() + 8).append('"');
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				case '\b' -> sb.append("\\b");
				case '\f' -> sb.append("\\f");
				default -> {
					if (c >= 0x20 && c <= 0x7e)
						sb.append(c);
					else
						sb.append(String.format("\\u%04x", (int) c));
				}
			}
		}
		return sb.append('"').toString();
	}

	// --- output ---

	private void line(String text) {
		out.append(indent).append(text).append('\n');
	}

	private void open(String text) {
		line(text);
		indent += "    ";
	}

	private void close(String text) {
		indent = indent.substring(4);
		line(text);
	}

	/**
	 * Ends the current block and starts an {@code else} (the caller continues the
	 * chain).
	 */
	private void mid(String text) {
		out.append(indent, 0, indent.length() - 4).append(text).append('\n');
	}

	private void mid() {
		indent = indent.substring(4);
	}

	private void blank() {
		out.append('\n');
	}
}
