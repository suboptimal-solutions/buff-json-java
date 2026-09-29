package io.suboptimal.buffjson.internal.codegen.cf;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.instruction.SwitchCase;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import io.suboptimal.buffjson.internal.codegen.ClassBackend;
import io.suboptimal.buffjson.internal.codegen.ClassDef;
import io.suboptimal.buffjson.internal.codegen.Expr;
import io.suboptimal.buffjson.internal.codegen.Members.FieldDef;
import io.suboptimal.buffjson.internal.codegen.Members.MethodDef;
import io.suboptimal.buffjson.internal.codegen.Stmt;
import io.suboptimal.buffjson.internal.codegen.Ty;
import io.suboptimal.buffjson.internal.codegen.Var;

/**
 * Lowers the code model to a class file with the Class-File API (final in Java
 * 24). What it writes is what javac would write for the source the protoc
 * plugin prints from the same model: the same locals, jumps, {@code switch}es
 * and calls, with the stack map frames worked out by the API.
 *
 * <p>
 * Only what the templates use is supported. Anything else throws, and the
 * caller treats that as "no generated class for this message" and uses the
 * slower runtime paths.
 */
public final class ClassFileBackend implements ClassBackend {

	@Override
	public byte[] lower(ClassDef definition, ClassLoader loader) {
		ClassHierarchyResolver resolver = ClassHierarchyResolver.defaultResolver()
				.orElse(ClassHierarchyResolver.ofClassLoading(loader));
		ClassFile classFile = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(resolver));
		ClassDesc self = desc(definition.name());
		return classFile.build(self, cb -> {
			cb.withVersion(ClassFile.JAVA_21_VERSION, 0);
			cb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER);
			cb.withSuperclass(ConstantDescs.CD_Object);
			cb.withInterfaceSymbols(definition.interfaces().stream().map(ClassFileBackend::desc).toList());

			List<FieldDef> initialised = new ArrayList<>();
			for (FieldDef field : definition.members().fields()) {
				cb.withField(field.name(), desc(field.type()), field.access());
				if (field.init() != null) {
					initialised.add(field);
				}
			}
			cb.withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC,
					code -> code.aload(0)
							.invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
							.return_());
			if (!initialised.isEmpty()) {
				cb.withMethodBody(ConstantDescs.CLASS_INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_STATIC, code -> {
					Lowering lowering = new Lowering(code, self, Ty.VOID);
					for (FieldDef field : initialised) {
						lowering.expression(field.init());
						lowering.coerce(field.init().type(), field.type());
						code.putstatic(self, field.name(), desc(field.type()));
					}
					code.return_();
				});
			}
			for (MethodDef method : definition.members().methods()) {
				MethodTypeDesc type = methodType(method.ret(), method.params());
				cb.withMethodBody(method.name(), type, method.access(),
						code -> new Lowering(code, self, method.ret()).method(method));
				if (method.bridge() != null) {
					cb.withMethodBody(method.name(), methodType(method.bridge(), method.params()),
							ClassFile.ACC_PUBLIC | ClassFile.ACC_BRIDGE | ClassFile.ACC_SYNTHETIC, code -> {
								code.aload(0);
								int slot = 1;
								for (Var parameter : method.params()) {
									TypeKind kind = kind(parameter.type()).asLoadable();
									code.loadLocal(kind, slot);
									slot += kind.slotSize();
								}
								code.invokevirtual(self, method.name(), type);
								code.return_(kind(method.ret()).asLoadable());
							});
				}
			}
		});
	}

	private static ClassDesc desc(Ty type) {
		return ClassDesc.ofDescriptor(type.descriptor());
	}

	private static MethodTypeDesc methodType(Ty ret, List<? extends Expr> parameters) {
		return MethodTypeDesc.of(desc(ret), parameters.stream().map(p -> desc(p.type())).toList());
	}

	private static MethodTypeDesc callType(Ty ret, List<Ty> parameters) {
		return MethodTypeDesc.of(desc(ret), parameters.stream().map(ClassFileBackend::desc).toList());
	}

	private static TypeKind kind(Ty type) {
		if (type instanceof Ty.Prim primitive) {
			return switch (primitive) {
				case BOOLEAN -> TypeKind.BOOLEAN;
				case BYTE -> TypeKind.BYTE;
				case CHAR -> TypeKind.CHAR;
				case SHORT -> TypeKind.SHORT;
				case INT -> TypeKind.INT;
				case LONG -> TypeKind.LONG;
				case FLOAT -> TypeKind.FLOAT;
				case DOUBLE -> TypeKind.DOUBLE;
				case VOID -> TypeKind.VOID;
			};
		}
		return TypeKind.REFERENCE;
	}

	/**
	 * What a value of this kind is on the operand stack: sub-int types are ints.
	 */
	private static TypeKind stackKind(Ty type) {
		return kind(type).asLoadable();
	}

	/** Lowers one method body. */
	private static final class Lowering {

		/** A loop or switch that {@code break} can leave, and whether anything does. */
		private static final class Exit {
			final Label label;
			boolean used;

			Exit(Label label) {
				this.label = label;
			}
		}

		private final CodeBuilder code;
		private final ClassDesc self;
		private final Ty returnType;
		private final Map<Var, Integer> slots = new IdentityHashMap<>();
		private final ArrayDeque<Exit> exits = new ArrayDeque<>();

		Lowering(CodeBuilder code, ClassDesc self, Ty returnType) {
			this.code = code;
			this.self = self;
			this.returnType = returnType;
		}

		void method(MethodDef method) {
			int index = 0;
			for (Var parameter : method.params()) {
				slots.put(parameter, code.parameterSlot(index++));
			}
			if (statements(method.body())) {
				if (returnType != Ty.VOID) {
					throw new IllegalStateException(method.name() + " can complete without returning a value");
				}
				code.return_();
			}
		}

		// --- statements; each returns whether control can reach the statement after it
		// ---

		private boolean statements(List<Stmt> body) {
			for (Stmt statement : body) {
				if (!statement(statement)) {
					return false;
				}
			}
			return true;
		}

		private boolean statement(Stmt statement) {
			switch (statement) {
				case Stmt.Decl s -> {
					int slot = code.allocateLocal(stackKind(s.var().type()));
					slots.put(s.var(), slot);
					if (s.init() != null) {
						expression(s.init());
						coerce(s.init().type(), s.var().type());
						code.storeLocal(stackKind(s.var().type()), slot);
					}
					return true;
				}
				case Stmt.Assign s -> {
					expression(s.value());
					coerce(s.value().type(), s.var().type());
					code.storeLocal(stackKind(s.var().type()), slot(s.var()));
					return true;
				}
				case Stmt.Exec s -> {
					expression(s.call());
					discard(s.call().type());
					return true;
				}
				case Stmt.If s -> {
					return ifStatement(s);
				}
				case Stmt.While s -> {
					return whileStatement(s);
				}
				case Stmt.Switch s -> {
					return switchStatement(s);
				}
				case Stmt.Break s -> {
					Exit exit = exits.peek();
					if (exit == null) {
						throw new IllegalStateException("break outside a loop or switch");
					}
					exit.used = true;
					code.goto_(exit.label);
					return false;
				}
				case Stmt.Return s -> {
					if (s.value() != null) {
						expression(s.value());
						coerce(s.value().type(), returnType);
					}
					code.return_(stackKind(returnType));
					return false;
				}
				case Stmt.Throw s -> {
					expression(s.value());
					code.athrow();
					return false;
				}
				case Stmt.Block s -> {
					return statements(s.body());
				}
			}
		}

		private boolean ifStatement(Stmt.If s) {
			Label otherwise = code.newLabel();
			jump(s.condition(), false, otherwise);
			boolean thenCompletes = statements(s.then());
			if (s.otherwise().isEmpty()) {
				code.labelBinding(otherwise);
				return true;
			}
			Label end = code.newLabel();
			if (thenCompletes) {
				code.goto_(end);
			}
			code.labelBinding(otherwise);
			boolean elseCompletes = statements(s.otherwise());
			if (thenCompletes) {
				code.labelBinding(end);
			}
			return thenCompletes || elseCompletes;
		}

		private boolean whileStatement(Stmt.While s) {
			Label top = code.newBoundLabel();
			Exit exit = new Exit(code.newLabel());
			if (!(s.condition() instanceof Expr.BoolLit literal && literal.value())) {
				exit.used = true;
				jump(s.condition(), false, exit.label);
			}
			exits.push(exit);
			boolean completes = statements(s.body());
			exits.pop();
			if (completes) {
				code.goto_(top);
			}
			if (exit.used) {
				code.labelBinding(exit.label);
				return true;
			}
			return false;
		}

		private boolean switchStatement(Stmt.Switch s) {
			expression(s.selector());
			coerce(s.selector().type(), Ty.INT);
			Exit exit = new Exit(code.newLabel());
			Label defaultLabel = s.defaultBody() != null ? code.newLabel() : exit.label;
			List<Label> labels = new ArrayList<>();
			List<SwitchCase> cases = new ArrayList<>();
			for (Stmt.Case c : s.cases()) {
				Label label = code.newLabel();
				labels.add(label);
				for (int value : c.labels()) {
					cases.add(SwitchCase.of(value, label));
				}
			}
			cases.sort(Comparator.comparingInt(SwitchCase::caseValue));
			if (useTable(cases)) {
				code.tableswitch(cases.getFirst().caseValue(), cases.getLast().caseValue(), defaultLabel, cases);
			} else {
				code.lookupswitch(defaultLabel, cases);
			}
			exits.push(exit);
			for (int i = 0; i < labels.size(); i++) {
				code.labelBinding(labels.get(i));
				if (statements(s.cases().get(i).body())) {
					exit.used = true;
					code.goto_(exit.label);
				}
			}
			boolean defaultCompletes = true;
			if (s.defaultBody() != null) {
				code.labelBinding(defaultLabel);
				defaultCompletes = statements(s.defaultBody());
			} else {
				exit.used = true; // no default: the end is where an unmatched value goes
			}
			exits.pop();
			if (defaultCompletes || exit.used) {
				code.labelBinding(exit.label);
				return true;
			}
			return false;
		}

		/** javac's choice between {@code tableswitch} and {@code lookupswitch}. */
		private static boolean useTable(List<SwitchCase> sorted) {
			if (sorted.isEmpty()) {
				return false;
			}
			long low = sorted.getFirst().caseValue();
			long high = sorted.getLast().caseValue();
			long count = sorted.size();
			long tableCost = 4 + (high - low + 1) + 3 * 3;
			long lookupCost = 3 + 2 * count + 3 * count;
			return tableCost <= lookupCost;
		}

		// --- conditions ---

		/**
		 * Emits code that jumps to {@code target} when {@code condition} is
		 * {@code jumpIf}, and otherwise falls through.
		 */
		private void jump(Expr condition, boolean jumpIf, Label target) {
			switch (condition) {
				case Expr.BoolLit literal -> {
					if (literal.value() == jumpIf) {
						code.goto_(target);
					}
				}
				case Expr.Not not -> jump(not.operand(), !jumpIf, target);
				case Expr.Binary b when b.op() == Expr.Op.LAND -> {
					if (!jumpIf) {
						jump(b.left(), false, target);
						jump(b.right(), false, target);
					} else {
						Label skip = code.newLabel();
						jump(b.left(), false, skip);
						jump(b.right(), true, target);
						code.labelBinding(skip);
					}
				}
				case Expr.Binary b when b.op() == Expr.Op.LOR -> {
					if (jumpIf) {
						jump(b.left(), true, target);
						jump(b.right(), true, target);
					} else {
						Label skip = code.newLabel();
						jump(b.left(), true, skip);
						jump(b.right(), false, target);
						code.labelBinding(skip);
					}
				}
				case Expr.Binary b when b.op().isComparison() -> compare(b, jumpIf, target);
				default -> {
					expression(condition);
					code.branch(jumpIf ? Opcode.IFNE : Opcode.IFEQ, target);
				}
			}
		}

		private void compare(Expr.Binary b, boolean jumpIf, Label target) {
			Expr.Op op = jumpIf ? b.op() : negate(b.op());
			Ty left = b.left().type();
			Ty right = b.right().type();
			if (!left.isPrimitive() || !right.isPrimitive()) {
				expression(b.left());
				if (b.right() instanceof Expr.NullLit) {
					code.branch(op == Expr.Op.EQ ? Opcode.IFNULL : Opcode.IFNONNULL, target);
					return;
				}
				expression(b.right());
				code.branch(op == Expr.Op.EQ ? Opcode.IF_ACMPEQ : Opcode.IF_ACMPNE, target);
				return;
			}
			Ty common = Expr.promote(left, right);
			if (common != Ty.INT && common != Ty.LONG) {
				throw new UnsupportedOperationException("comparison of " + common.source());
			}
			expression(b.left());
			coerce(left, common);
			if (common == Ty.INT && b.right() instanceof Expr.IntLit zero && zero.value() == 0) {
				code.branch(zeroTest(op), target);
				return;
			}
			expression(b.right());
			coerce(right, common);
			if (common == Ty.INT) {
				code.branch(intCompare(op), target);
			} else {
				code.lcmp();
				code.branch(zeroTest(op), target);
			}
		}

		private static Expr.Op negate(Expr.Op op) {
			return switch (op) {
				case EQ -> Expr.Op.NE;
				case NE -> Expr.Op.EQ;
				case LT -> Expr.Op.GE;
				case GE -> Expr.Op.LT;
				case GT -> Expr.Op.LE;
				case LE -> Expr.Op.GT;
				default -> throw new IllegalArgumentException(op.symbol);
			};
		}

		private static Opcode intCompare(Expr.Op op) {
			return switch (op) {
				case EQ -> Opcode.IF_ICMPEQ;
				case NE -> Opcode.IF_ICMPNE;
				case LT -> Opcode.IF_ICMPLT;
				case GE -> Opcode.IF_ICMPGE;
				case GT -> Opcode.IF_ICMPGT;
				case LE -> Opcode.IF_ICMPLE;
				default -> throw new IllegalArgumentException(op.symbol);
			};
		}

		private static Opcode zeroTest(Expr.Op op) {
			return switch (op) {
				case EQ -> Opcode.IFEQ;
				case NE -> Opcode.IFNE;
				case LT -> Opcode.IFLT;
				case GE -> Opcode.IFGE;
				case GT -> Opcode.IFGT;
				case LE -> Opcode.IFLE;
				default -> throw new IllegalArgumentException(op.symbol);
			};
		}

		// --- expressions: each leaves its value on the operand stack ---

		void expression(Expr expression) {
			switch (expression) {
				case Var v -> code.loadLocal(stackKind(v.type()), slot(v));
				case Expr.IntLit lit -> code.loadConstant(Integer.valueOf(lit.value()));
				case Expr.LongLit lit -> code.loadConstant(Long.valueOf(lit.value()));
				case Expr.BoolLit lit -> code.loadConstant(Integer.valueOf(lit.value() ? 1 : 0));
				case Expr.StrLit lit -> code.loadConstant(lit.value());
				case Expr.NullLit lit -> code.aconst_null();
				case Expr.ArrayLoad load -> {
					expression(load.array());
					expression(load.index());
					coerce(load.index().type(), Ty.INT);
					code.arrayLoad(kind(load.type()));
				}
				case Expr.Binary b -> binary(b);
				case Expr.Not n -> booleanValue(n);
				case Expr.Cast c -> {
					expression(c.operand());
					convert(c.operand().type(), c.to());
				}
				case Expr.Call c -> call(c);
				case Expr.New n -> {
					ClassDesc type = desc(n.type());
					code.new_(type);
					code.dup();
					arguments(n.args(), n.params());
					code.invokespecial(type, ConstantDescs.INIT_NAME, callType(Ty.VOID, n.params()));
				}
				case Expr.StaticGet g -> code.getstatic(desc(g.owner()), g.name(), desc(g.type()));
				case Expr.ClassLit c -> code.loadConstant(desc(c.target()));
			}
		}

		private void binary(Expr.Binary b) {
			Expr.Op op = b.op();
			if (op.isComparison() || op.isLogical()) {
				booleanValue(b);
				return;
			}
			Ty type = b.type();
			expression(b.left());
			coerce(b.left().type(), type);
			expression(b.right());
			if (op.isShift()) {
				if (b.right().type() == Ty.LONG) {
					code.conversion(TypeKind.LONG, TypeKind.INT);
				}
			} else {
				coerce(b.right().type(), type);
			}
			boolean wide = type == Ty.LONG;
			if (!wide && type != Ty.INT) {
				throw new UnsupportedOperationException(op.symbol + " on " + type.source());
			}
			switch (op) {
				case ADD -> {
					if (wide)
						code.ladd();
					else
						code.iadd();
				}
				case SUB -> {
					if (wide)
						code.lsub();
					else
						code.isub();
				}
				case MUL -> {
					if (wide)
						code.lmul();
					else
						code.imul();
				}
				case AND -> {
					if (wide)
						code.land();
					else
						code.iand();
				}
				case OR -> {
					if (wide)
						code.lor();
					else
						code.ior();
				}
				case XOR -> {
					if (wide)
						code.lxor();
					else
						code.ixor();
				}
				case SHL -> {
					if (wide)
						code.lshl();
					else
						code.ishl();
				}
				case SHR -> {
					if (wide)
						code.lshr();
					else
						code.ishr();
				}
				case USHR -> {
					if (wide)
						code.lushr();
					else
						code.iushr();
				}
				default -> throw new IllegalArgumentException(op.symbol);
			}
		}

		/**
		 * A comparison, {@code &&}, {@code ||} or {@code !} used as a value: 0 or 1.
		 */
		private void booleanValue(Expr condition) {
			Label isFalse = code.newLabel();
			Label end = code.newLabel();
			jump(condition, false, isFalse);
			code.iconst_1();
			code.goto_(end);
			code.labelBinding(isFalse);
			code.iconst_0();
			code.labelBinding(end);
		}

		private void call(Expr.Call c) {
			if (c.receiver() != null) {
				expression(c.receiver());
			}
			arguments(c.args(), c.params());
			ClassDesc owner = desc(c.owner());
			MethodTypeDesc type = callType(c.ret(), c.params());
			switch (c.kind()) {
				case STATIC -> code.invokestatic(owner, c.name(), type);
				case VIRTUAL -> code.invokevirtual(owner, c.name(), type);
				case INTERFACE -> code.invokeinterface(owner, c.name(), type);
			}
		}

		private void arguments(List<Expr> args, List<Ty> params) {
			for (int i = 0; i < args.size(); i++) {
				expression(args.get(i));
				coerce(args.get(i).type(), params.get(i));
			}
		}

		// --- conversions and locals ---

		/**
		 * Java's implicit widening between primitive types (references are left to the
		 * verifier).
		 */
		void coerce(Ty from, Ty to) {
			if (from.equals(to) || !from.isPrimitive() || !to.isPrimitive()) {
				return;
			}
			TypeKind source = stackKind(from);
			TypeKind target = stackKind(to);
			if (source != target) {
				code.conversion(source, target);
			}
		}

		/** An explicit cast. */
		private void convert(Ty from, Ty to) {
			if (!to.isPrimitive()) {
				if (!from.equals(to)) {
					code.checkcast(desc(to));
				}
				return;
			}
			TypeKind source = stackKind(from);
			TypeKind target = kind(to);
			if (target == TypeKind.BYTE || target == TypeKind.SHORT || target == TypeKind.CHAR) {
				if (source != TypeKind.INT) {
					code.conversion(source, TypeKind.INT);
				}
				code.conversion(TypeKind.INT, target);
			} else if (source != stackKind(to)) {
				code.conversion(source, target);
			}
		}

		private void discard(Ty type) {
			if (type == Ty.VOID) {
				return;
			}
			if (type.isWide()) {
				code.pop2();
			} else {
				code.pop();
			}
		}

		private int slot(Var var) {
			Integer slot = slots.get(var);
			if (slot == null) {
				throw new IllegalStateException("variable " + var.name() + " used before its declaration");
			}
			return slot;
		}
	}
}
