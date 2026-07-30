package io.suboptimal.buffjson.internal;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;

import io.suboptimal.buffjson.internal.typed.FieldName;

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
	private final Map<String, FieldInfo> fieldsByJsonName;

	private MessageSchema(Descriptor descriptor) {
		var fieldDescriptors = descriptor.getFields();
		this.fields = new FieldInfo[fieldDescriptors.size()];
		this.fieldsByJsonName = new HashMap<>(fieldDescriptors.size() * 2);
		for (int i = 0; i < fieldDescriptors.size(); i++) {
			FieldInfo fi = new FieldInfo(fieldDescriptors.get(i));
			this.fields[i] = fi;
			fieldsByJsonName.put(fi.jsonName(), fi);
			// Proto3 JSON spec: parsers must accept both jsonName and original name
			String protoName = fieldDescriptors.get(i).getName();
			if (!protoName.equals(fi.jsonName())) {
				fieldsByJsonName.put(protoName, fi);
			}
		}
	}

	public static MessageSchema forDescriptor(Descriptor descriptor) {
		return CACHE.computeIfAbsent(descriptor, MessageSchema::new);
	}

	public FieldInfo[] fields() {
		return fields;
	}

	/**
	 * Looks up a field by its JSON name or original proto name. Returns null if not
	 * found.
	 */
	public FieldInfo fieldByJsonName(String name) {
		return fieldsByJsonName.get(name);
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
		private final FieldName name;
		private final FieldDescriptor.JavaType javaType;
		private final boolean isRepeated;
		private final boolean isMapField;
		private final boolean hasPresence;
		private final FieldDescriptor mapKeyDescriptor;
		private final FieldDescriptor mapValueDescriptor;

		FieldInfo(FieldDescriptor fd) {
			this.descriptor = fd;
			this.jsonName = fd.getJsonName();
			this.name = FieldName.of(this.jsonName);
			this.javaType = fd.getJavaType();
			this.isRepeated = fd.isRepeated();
			this.isMapField = fd.isMapField();
			this.hasPresence = fd.hasPresence();
			this.mapKeyDescriptor = fd.isMapField() ? fd.getMessageType().findFieldByName("key") : null;
			this.mapValueDescriptor = fd.isMapField() ? fd.getMessageType().findFieldByName("value") : null;
		}

		public FieldDescriptor descriptor() {
			return descriptor;
		}

		public String jsonName() {
			return jsonName;
		}

		/**
		 * Pre-encoded {@code "fieldName":}. Write it with
		 * {@link FieldName#writeTo(com.alibaba.fastjson2.JSONWriter)} rather than
		 * reaching for the arrays — a {@code json_name} that is not raw-writable has
		 * none, and only {@code writeTo} knows to escape it instead.
		 */
		public FieldName name() {
			return name;
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

		/**
		 * The synthetic map-entry {@code key} field, resolved once here so the write
		 * path never calls {@code findFieldByName} per map write.
		 */
		public FieldDescriptor mapKeyDescriptor() {
			return mapKeyDescriptor;
		}

		public FieldDescriptor mapValueDescriptor() {
			return mapValueDescriptor;
		}
	}
}
