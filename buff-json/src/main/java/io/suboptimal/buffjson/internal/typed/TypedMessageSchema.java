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
 * creation per Descriptor. A {@code FAILED} sentinel marks descriptors where
 * generation failed, so the reflection path is used.
 */
public final class TypedMessageSchema {

	private static final TypedMessageSchema FAILED = new TypedMessageSchema(null);

	private static final ConcurrentHashMap<Descriptor, TypedMessageSchema> CACHE = new ConcurrentHashMap<>();

	private final TypedFieldAccessor[] fields;

	private TypedMessageSchema(TypedFieldAccessor[] fields) {
		this.fields = fields;
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
	}

	private static TypedMessageSchema build(Descriptor descriptor, Class<? extends Message> messageClass) {
		// Build a single accessor list in descriptor (field-number) order. A oneof's
		// accessor is inserted at the position of its first-declared member, so output
		// field order matches JsonFormat (rather than bunching oneofs at the end).
		var fieldList = new ArrayList<TypedFieldAccessor>();
		var emittedOneofs = new HashSet<OneofDescriptor>();
		for (FieldDescriptor fd : descriptor.getFields()) {
			OneofDescriptor oneof = fd.getRealContainingOneof();
			if (oneof != null) {
				if (emittedOneofs.add(oneof)) {
					var accessors = TypedFieldAccessorFactory.createOneofAccessors(oneof, messageClass);
					if (accessors == null)
						return FAILED;
					var members = oneof.getFields();
					int[] fieldNumbers = new int[members.size()];
					for (int i = 0; i < members.size(); i++) {
						fieldNumbers[i] = members.get(i).getNumber();
					}
					fieldList.add(new TypedFieldAccessor.OneofAccessor(oneof, fieldNumbers, accessors));
				}
				continue;
			}
			if (fd.getOptions().hasDeprecated() && fd.getOptions().getDeprecated())
				continue;
			var accessor = TypedFieldAccessorFactory.create(fd, messageClass);
			if (accessor == null)
				return FAILED;
			fieldList.add(accessor);
		}

		return new TypedMessageSchema(fieldList.toArray(TypedFieldAccessor[]::new));
	}
}
