package io.suboptimal.buffjson.internal.typed;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;

import com.alibaba.fastjson2.JSONWriter;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.OneofDescriptor;
import com.google.protobuf.Message;

import io.suboptimal.buffjson.internal.FieldWriter;
import io.suboptimal.buffjson.internal.ProtobufMessageWriter;
import io.suboptimal.buffjson.internal.WellKnownTypes;

/**
 * Pre-compiled typed accessor for a single protobuf field. Uses
 * LambdaMetafactory-generated functions to call typed getters directly,
 * avoiding {@code message.getField(fd)} reflection and primitive boxing.
 *
 * <p>
 * Each record variant handles one proto field type with full proto3 JSON
 * semantics (skip defaults, unsigned ints, quoted int64, NaN/Infinity, enum
 * names, etc.).
 */
public sealed interface TypedFieldAccessor {

	void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer);

	/**
	 * Writes an enum value by name. Uses the pre-built dense name array for the
	 * common non-negative case, falling back to a descriptor lookup for negative or
	 * sparse values (so named negatives like {@code NEG = -1} serialize by name);
	 * genuinely unknown numbers serialize as the integer.
	 */
	static void writeEnumName(JSONWriter jw, int ev, String[] names, EnumDescriptor enumType) {
		String enumName = ev >= 0 && ev < names.length ? names[ev] : null;
		if (enumName == null) {
			var vd = enumType.findValueByNumber(ev);
			if (vd != null)
				enumName = vd.getName();
		}
		if (enumName != null)
			jw.writeString(enumName);
		else
			jw.writeInt32(ev);
	}

	record IntAccessor(ToIntFunction<Message> getter, boolean unsigned, FieldName name) implements TypedFieldAccessor {
		@Override
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer) {
			int v = getter.applyAsInt(msg);
			if (v == 0)
				return;
			name.writeTo(jw);
			if (unsigned)
				jw.writeInt64(Integer.toUnsignedLong(v));
			else
				jw.writeInt32(v);
		}
	}

	record LongAccessor(ToLongFunction<Message> getter, boolean unsigned,
			FieldName name) implements TypedFieldAccessor {
		@Override
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer) {
			long v = getter.applyAsLong(msg);
			if (v == 0L)
				return;
			name.writeTo(jw);
			if (unsigned)
				WellKnownTypes.writeUnsignedLongString(jw, v);
			else
				jw.writeString(v);
		}
	}

	record FloatAccessor(ToDoubleFunction<Message> getter, FieldName name) implements TypedFieldAccessor {
		@Override
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer) {
			float v = (float) getter.applyAsDouble(msg);
			if (Float.floatToRawIntBits(v) == 0)
				return;
			name.writeTo(jw);
			if (Float.isFinite(v))
				jw.writeFloat(v);
			else if (Float.isNaN(v))
				jw.writeString("NaN");
			else
				jw.writeString(v > 0 ? "Infinity" : "-Infinity");
		}
	}

	record DoubleAccessor(ToDoubleFunction<Message> getter, FieldName name) implements TypedFieldAccessor {
		@Override
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer) {
			double v = getter.applyAsDouble(msg);
			if (Double.doubleToRawLongBits(v) == 0)
				return;
			name.writeTo(jw);
			if (Double.isFinite(v))
				jw.writeDouble(v);
			else if (Double.isNaN(v))
				jw.writeString("NaN");
			else
				jw.writeString(v > 0 ? "Infinity" : "-Infinity");
		}
	}

	record BoolAccessor(Predicate<Message> getter, FieldName name) implements TypedFieldAccessor {
		@Override
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer) {
			if (getter.test(msg)) {
				name.writeTo(jw);
				jw.writeBool(true);
			}
		}
	}

	record StringAccessor(Function<Message, String> getter, FieldName name) implements TypedFieldAccessor {
		@Override
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer) {
			String v = getter.apply(msg);
			if (v.isEmpty())
				return;
			name.writeTo(jw);
			jw.writeString(v);
		}
	}

	record ByteStringAccessor(Function<Message, ByteString> getter, FieldName name) implements TypedFieldAccessor {
		@Override
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer) {
			ByteString v = getter.apply(msg);
			if (v.isEmpty())
				return;
			name.writeTo(jw);
			jw.writeBase64(v.toByteArray());
		}
	}

	record EnumAccessor(ToIntFunction<Message> valueGetter, String[] names, EnumDescriptor enumType, boolean nullValue,
			FieldName name) implements TypedFieldAccessor {
		@Override
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer) {
			int ev = valueGetter.applyAsInt(msg);
			if (ev == 0)
				return;
			name.writeTo(jw);
			if (nullValue)
				jw.writeNull();
			else
				writeEnumName(jw, ev, names, enumType);
		}
	}

	// --- Presence fields (explicit has-getter) ---

	record PresenceIntAccessor(ToIntFunction<Message> getter, Predicate<Message> has, boolean unsigned,
			FieldName name) implements TypedFieldAccessor {
		@Override
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer) {
			if (!has.test(msg))
				return;
			name.writeTo(jw);
			int v = getter.applyAsInt(msg);
			if (unsigned)
				jw.writeInt64(Integer.toUnsignedLong(v));
			else
				jw.writeInt32(v);
		}
	}

	record PresenceLongAccessor(ToLongFunction<Message> getter, Predicate<Message> has, boolean unsigned,
			FieldName name) implements TypedFieldAccessor {
		@Override
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer) {
			if (!has.test(msg))
				return;
			name.writeTo(jw);
			long v = getter.applyAsLong(msg);
			if (unsigned)
				WellKnownTypes.writeUnsignedLongString(jw, v);
			else
				jw.writeString(v);
		}
	}

	record PresenceFloatAccessor(ToDoubleFunction<Message> getter, Predicate<Message> has,
			FieldName name) implements TypedFieldAccessor {
		@Override
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer) {
			if (!has.test(msg))
				return;
			name.writeTo(jw);
			float v = (float) getter.applyAsDouble(msg);
			if (Float.isFinite(v))
				jw.writeFloat(v);
			else if (Float.isNaN(v))
				jw.writeString("NaN");
			else
				jw.writeString(v > 0 ? "Infinity" : "-Infinity");
		}
	}

	record PresenceDoubleAccessor(ToDoubleFunction<Message> getter, Predicate<Message> has,
			FieldName name) implements TypedFieldAccessor {
		@Override
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer) {
			if (!has.test(msg))
				return;
			name.writeTo(jw);
			double v = getter.applyAsDouble(msg);
			if (Double.isFinite(v))
				jw.writeDouble(v);
			else if (Double.isNaN(v))
				jw.writeString("NaN");
			else
				jw.writeString(v > 0 ? "Infinity" : "-Infinity");
		}
	}

	record PresenceBoolAccessor(Predicate<Message> getter, Predicate<Message> has,
			FieldName name) implements TypedFieldAccessor {
		@Override
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer) {
			if (!has.test(msg))
				return;
			name.writeTo(jw);
			jw.writeBool(getter.test(msg));
		}
	}

	record PresenceStringAccessor(Function<Message, String> getter, Predicate<Message> has,
			FieldName name) implements TypedFieldAccessor {
		@Override
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer) {
			if (!has.test(msg))
				return;
			name.writeTo(jw);
			jw.writeString(getter.apply(msg));
		}
	}

	record PresenceByteStringAccessor(Function<Message, ByteString> getter, Predicate<Message> has,
			FieldName name) implements TypedFieldAccessor {
		@Override
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer) {
			if (!has.test(msg))
				return;
			name.writeTo(jw);
			jw.writeBase64(getter.apply(msg).toByteArray());
		}
	}

	record PresenceEnumAccessor(ToIntFunction<Message> valueGetter, Predicate<Message> has, String[] names,
			EnumDescriptor enumType, boolean nullValue, FieldName name) implements TypedFieldAccessor {
		@Override
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer) {
			if (!has.test(msg))
				return;
			name.writeTo(jw);
			if (nullValue) {
				jw.writeNull();
				return;
			}
			writeEnumName(jw, valueGetter.applyAsInt(msg), names, enumType);
		}
	}

	record PresenceMessageAccessor(Function<Message, Message> getter, Predicate<Message> has,
			FieldName name) implements TypedFieldAccessor {
		@Override
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer) {
			if (!has.test(msg))
				return;
			name.writeTo(jw);
			Message nested = getter.apply(msg);
			if (WellKnownTypes.isWellKnownType(nested.getDescriptorForType()))
				WellKnownTypes.write(jw, nested, writer);
			else
				writer.writeMessage(jw, nested);
		}
	}

	// --- Repeated fields ---

	record RepeatedAccessor(Function<Message, List<?>> listGetter, FieldDescriptor fd,
			FieldName name) implements TypedFieldAccessor {
		@Override
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer) {
			List<?> values = listGetter.apply(msg);
			if (values.isEmpty())
				return;
			name.writeTo(jw);
			FieldWriter.writeRepeated(jw, fd, values, writer);
		}
	}

	@SuppressWarnings("unchecked")
	record RepeatedIntAccessor(Function<Message, List<?>> listGetter, boolean unsigned,
			FieldName name) implements TypedFieldAccessor {
		@Override
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer) {
			List<Integer> values = (List<Integer>) (List<?>) listGetter.apply(msg);
			if (values.isEmpty())
				return;
			name.writeTo(jw);
			jw.startArray();
			for (int i = 0; i < values.size(); i++) {
				if (i > 0)
					jw.writeComma();
				if (unsigned)
					jw.writeInt64(Integer.toUnsignedLong(values.get(i)));
				else
					jw.writeInt32(values.get(i));
			}
			jw.endArray();
		}
	}

	@SuppressWarnings("unchecked")
	record RepeatedLongAccessor(Function<Message, List<?>> listGetter, boolean unsigned,
			FieldName name) implements TypedFieldAccessor {
		@Override
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer) {
			List<Long> values = (List<Long>) (List<?>) listGetter.apply(msg);
			if (values.isEmpty())
				return;
			name.writeTo(jw);
			jw.startArray();
			for (int i = 0; i < values.size(); i++) {
				if (i > 0)
					jw.writeComma();
				if (unsigned)
					WellKnownTypes.writeUnsignedLongString(jw, values.get(i));
				else
					jw.writeString(values.get(i));
			}
			jw.endArray();
		}
	}

	@SuppressWarnings("unchecked")
	record RepeatedStringAccessor(Function<Message, List<?>> listGetter, FieldName name) implements TypedFieldAccessor {
		@Override
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer) {
			List<String> values = (List<String>) (List<?>) listGetter.apply(msg);
			if (values.isEmpty())
				return;
			name.writeTo(jw);
			jw.startArray();
			for (int i = 0; i < values.size(); i++) {
				if (i > 0)
					jw.writeComma();
				jw.writeString(values.get(i));
			}
			jw.endArray();
		}
	}

	@SuppressWarnings("unchecked")
	record RepeatedMessageAccessor(Function<Message, List<?>> listGetter,
			FieldName name) implements TypedFieldAccessor {
		@Override
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer) {
			List<Message> values = (List<Message>) (List<?>) listGetter.apply(msg);
			if (values.isEmpty())
				return;
			name.writeTo(jw);
			jw.startArray();
			for (int i = 0; i < values.size(); i++) {
				if (i > 0)
					jw.writeComma();
				Message nested = values.get(i);
				if (WellKnownTypes.isWellKnownType(nested.getDescriptorForType()))
					WellKnownTypes.write(jw, nested, writer);
				else
					writer.writeMessage(jw, nested);
			}
			jw.endArray();
		}
	}

	record RepeatedEnumAccessor(Function<Message, List<?>> valueListGetter, String[] names, EnumDescriptor enumType,
			boolean nullValue, FieldName name) implements TypedFieldAccessor {
		@Override
		@SuppressWarnings("unchecked")
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer) {
			List<Integer> values = (List<Integer>) (List<?>) valueListGetter.apply(msg);
			if (values.isEmpty())
				return;
			name.writeTo(jw);
			jw.startArray();
			for (int i = 0; i < values.size(); i++) {
				if (i > 0)
					jw.writeComma();
				if (nullValue)
					jw.writeNull();
				else
					writeEnumName(jw, values.get(i), names, enumType);
			}
			jw.endArray();
		}
	}

	// --- Map fields ---

	record MapAccessor(Function<Message, List<?>> entriesGetter, FieldDescriptor mapValueDescriptor,
			FieldName name) implements TypedFieldAccessor {
		@Override
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer) {
			List<?> entries = entriesGetter.apply(msg);
			if (entries.isEmpty())
				return;
			name.writeTo(jw);
			FieldWriter.writeMap(jw, mapValueDescriptor, entries, writer);
		}
	}

	record TypedMapAccessor(Function<Message, java.util.Map<?, ?>> mapGetter, FieldDescriptor valueFd,
			boolean stringKey, FieldName name) implements TypedFieldAccessor {
		@Override
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer) {
			java.util.Map<?, ?> map = mapGetter.apply(msg);
			if (map.isEmpty())
				return;
			name.writeTo(jw);
			jw.startObject();
			for (var entry : map.entrySet()) {
				if (stringKey)
					jw.writeName((String) entry.getKey());
				else
					jw.writeName(entry.getKey().toString());
				jw.writeColon();
				FieldWriter.writeValue(jw, valueFd, entry.getValue(), writer);
			}
			jw.endObject();
		}
	}

	/**
	 * Writes the single set field of a oneof (or nothing if unset). Placed in the
	 * field list at the position of the oneof's first-declared member so output
	 * field order matches JsonFormat's field-number order.
	 */
	record OneofAccessor(OneofDescriptor oneof, int[] fieldNumbers,
			TypedFieldAccessor[] accessors) implements TypedFieldAccessor {
		@Override
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer) {
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
