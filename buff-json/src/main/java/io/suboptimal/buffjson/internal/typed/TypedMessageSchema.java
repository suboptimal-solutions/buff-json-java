package io.suboptimal.buffjson.internal.typed;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;

import com.alibaba.fastjson2.JSONWriter;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.OneofDescriptor;
import com.google.protobuf.Message;

import io.suboptimal.buffjson.internal.ProtobufMessageWriter;

/**
 * Cached typed accessors for a protobuf message type, built lazily on first
 * use. Uses {@link TypedFieldAccessorFactory} to create LambdaMetafactory-based
 * accessors that call typed getters directly.
 *
 * <p>
 * Thread-safe: uses {@link ConcurrentHashMap#computeIfAbsent} for at-most-once
 * creation per Descriptor. A {@code null} value in the cache means generation
 * failed and the reflection path should be used.
 */
public final class TypedMessageSchema {

	/** Sentinel: generation was attempted but failed. */
	private static final TypedMessageSchema FAILED = new TypedMessageSchema(null, null);

	private static final ConcurrentHashMap<Descriptor, TypedMessageSchema> CACHE = new ConcurrentHashMap<>();

	private final TypedFieldAccessor[] fields;
	private final OneofGroup[] oneofs;

	private TypedMessageSchema(TypedFieldAccessor[] fields, OneofGroup[] oneofs) {
		this.fields = fields;
		this.oneofs = oneofs;
	}

	/**
	 * Returns the typed schema for the given message type, or {@code null} if
	 * generation failed (caller should fall back to reflection).
	 */
	public static TypedMessageSchema forMessage(Descriptor descriptor, Class<? extends Message> messageClass) {
		var cached = CACHE.get(descriptor);
		if (cached != null) {
			return cached == FAILED ? null : cached;
		}
		var schema = CACHE.computeIfAbsent(descriptor, desc -> {
			try {
				return build(desc, messageClass);
			} catch (Throwable e) {
				return FAILED;
			}
		});
		return schema == FAILED ? null : schema;
	}

	public void writeFields(JSONWriter jw, Message msg, ProtobufMessageWriter writer) {
		for (var accessor : fields) {
			accessor.write(jw, msg, writer);
		}
		for (var oneof : oneofs) {
			oneof.write(jw, msg, writer);
		}
	}

	private static TypedMessageSchema build(Descriptor descriptor, Class<? extends Message> messageClass) {
		// Collect fields that are part of oneofs
		var oneofFields = new HashSet<FieldDescriptor>();
		for (OneofDescriptor oneof : descriptor.getRealOneofs()) {
			oneofFields.addAll(oneof.getFields());
		}

		// Regular fields (not in oneof)
		var fieldList = new ArrayList<TypedFieldAccessor>();
		for (FieldDescriptor fd : descriptor.getFields()) {
			if (fd.getOptions().hasDeprecated() && fd.getOptions().getDeprecated())
				continue;
			if (oneofFields.contains(fd))
				continue;
			var accessor = TypedFieldAccessorFactory.create(fd, messageClass);
			if (accessor == null)
				return FAILED;
			fieldList.add(accessor);
		}

		// Oneof groups
		var oneofList = new ArrayList<OneofGroup>();
		for (OneofDescriptor oneof : descriptor.getRealOneofs()) {
			var accessors = TypedFieldAccessorFactory.createOneofAccessors(oneof, messageClass);
			if (accessors == null)
				return FAILED;
			// Build field number -> index mapping for the switch
			int[] fieldNumbers = new int[oneof.getFields().size()];
			for (int i = 0; i < oneof.getFields().size(); i++) {
				fieldNumbers[i] = oneof.getFields().get(i).getNumber();
			}
			oneofList.add(new OneofGroup(oneof, fieldNumbers, accessors));
		}

		return new TypedMessageSchema(fieldList.toArray(TypedFieldAccessor[]::new),
				oneofList.toArray(OneofGroup[]::new));
	}

	/**
	 * A group of fields sharing a oneof. At most one field is set at a time.
	 */
	private record OneofGroup(OneofDescriptor oneof, int[] fieldNumbers, TypedFieldAccessor[] accessors) {
		void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer) {
			FieldDescriptor setField = msg.getOneofFieldDescriptor(oneof);
			if (setField == null)
				return;
			int number = setField.getNumber();
			for (int i = 0; i < fieldNumbers.length; i++) {
				if (fieldNumbers[i] == number) {
					accessors[i].write(jw, msg, writer);
					return;
				}
			}
		}
	}
}
