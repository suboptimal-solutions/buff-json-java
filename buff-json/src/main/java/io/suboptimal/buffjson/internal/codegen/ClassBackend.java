package io.suboptimal.buffjson.internal.codegen;

/**
 * Turns a {@link ClassDef} into the bytes of a class file. The only
 * implementation uses the Class-File API of Java 24+ and is loaded by name, so
 * the library stays loadable (and the plugin's source output usable) on older
 * runtimes.
 */
public interface ClassBackend {

	/**
	 * @param loader
	 *            the loader the class will be defined in; the backend may load
	 *            classes through it to work out the types at control-flow joins
	 */
	byte[] lower(ClassDef definition, ClassLoader loader);
}
