package io.suboptimal.buffjson.internal;

import java.util.HashMap;
import java.util.Map;
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
		private final char[] nameWithColon;
		private final FieldDescriptor.JavaType javaType;
		private final boolean isRepeated;
		private final boolean isMapField;
		private final boolean hasPresence;
		private final FieldDescriptor mapValueDescriptor;

		FieldInfo(FieldDescriptor fd) {
			this.descriptor = fd;
			this.jsonName = fd.getJsonName();
			this.nameWithColon = buildNameWithColon(this.jsonName);
			this.javaType = fd.getJavaType();
			this.isRepeated = fd.isRepeated();
			this.isMapField = fd.isMapField();
			this.hasPresence = fd.hasPresence();
			this.mapValueDescriptor = fd.isMapField() ? fd.getMessageType().findFieldByName("value") : null;
		}

		/**
		 * Pre-computes {@code "fieldName":} as a char array for use with
		 * {@link com.alibaba.fastjson2.JSONWriter#writeNameRaw(char[])}. Works with
		 * both UTF-8 and UTF-16 writers. Protobuf JSON field names are always ASCII.
		 */
		private static char[] buildNameWithColon(String name) {
			char[] chars = new char[name.length() + 3];
			chars[0] = '"';
			name.getChars(0, name.length(), chars, 1);
			chars[name.length() + 1] = '"';
			chars[name.length() + 2] = ':';
			return chars;
		}

		public FieldDescriptor descriptor() {
			return descriptor;
		}

		public String jsonName() {
			return jsonName;
		}

		public char[] nameWithColon() {
			return nameWithColon;
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
