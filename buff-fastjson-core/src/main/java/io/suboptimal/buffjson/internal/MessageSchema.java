package io.suboptimal.buffjson.internal;

import java.util.concurrent.ConcurrentHashMap;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;

/**
 * Cached metadata for a protobuf message type, built from its
 * {@link Descriptor}.
 *
 * <p>
 * Each {@code MessageSchema} holds a pre-computed array of {@link FieldInfo}
 * records (one per field in the Descriptor). This avoids the overhead of
 * calling {@code Descriptor.getFields()} and extracting metadata on every
 * serialization call.
 *
 * <p>
 * Schemas are cached in a {@link ConcurrentHashMap} keyed by {@link Descriptor}
 * (which is an immutable singleton per message type). Thread-safe, lock-free
 * reads after initial population.
 */
public final class MessageSchema {

	private static final ConcurrentHashMap<Descriptor, MessageSchema> CACHE = new ConcurrentHashMap<>();

	private final FieldInfo[] fields;

	private MessageSchema(Descriptor descriptor) {
		var fieldDescriptors = descriptor.getFields();
		this.fields = new FieldInfo[fieldDescriptors.size()];
		for (int i = 0; i < fieldDescriptors.size(); i++) {
			this.fields[i] = new FieldInfo(fieldDescriptors.get(i));
		}
	}

	public static MessageSchema forDescriptor(Descriptor descriptor) {
		return CACHE.computeIfAbsent(descriptor, MessageSchema::new);
	}

	public FieldInfo[] fields() {
		return fields;
	}

	/**
	 * Pre-computed metadata for a single protobuf field, avoiding repeated
	 * Descriptor lookups.
	 *
	 * <p>
	 * Caches: the JSON field name (camelCase via
	 * {@link FieldDescriptor#getJsonName()}), the Java type, and boolean flags for
	 * repeated/map/presence semantics.
	 */
	public static final class FieldInfo {
		private final FieldDescriptor descriptor;
		private final String jsonName;
		private final FieldDescriptor.JavaType javaType;
		private final boolean isRepeated;
		private final boolean isMapField;
		private final boolean hasPresence;
		private final FieldDescriptor mapValueDescriptor;

		FieldInfo(FieldDescriptor fd) {
			this.descriptor = fd;
			this.jsonName = fd.getJsonName();
			this.javaType = fd.getJavaType();
			this.isRepeated = fd.isRepeated();
			this.isMapField = fd.isMapField();
			this.hasPresence = fd.hasPresence();
			this.mapValueDescriptor = fd.isMapField() ? fd.getMessageType().findFieldByName("value") : null;
		}

		public FieldDescriptor descriptor() {
			return descriptor;
		}

		public String jsonName() {
			return jsonName;
		}

		public FieldDescriptor.JavaType javaType() {
			return javaType;
		}

		public boolean isRepeated() {
			return isRepeated;
		}

		public boolean isMapField() {
			return isMapField;
		}

		public boolean hasPresence() {
			return hasPresence;
		}

		public FieldDescriptor mapValueDescriptor() {
			return mapValueDescriptor;
		}
	}
}
