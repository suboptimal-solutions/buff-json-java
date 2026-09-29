package io.suboptimal.buffjson.internal.codegen;

import java.util.List;

/**
 * A whole class, for backends that produce one (the class-file backend). The
 * class is {@code public final}, extends {@code Object}, and gets a default
 * constructor; static field initialisers become its class initialiser.
 */
public record ClassDef(Ty.Obj name, List<Ty.Obj> interfaces, Members members) {
}
