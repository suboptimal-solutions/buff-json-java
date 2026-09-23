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
import com.google.protobuf.Duration;
import com.google.protobuf.Message;
import com.google.protobuf.Timestamp;

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

	void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer, boolean utf8);

	static void writeTimestampValue(JSONWriter jw, Message message, ProtobufMessageWriter writer) {
		if (message instanceof Timestamp timestamp)
			WellKnownTypes.writeTimestampDirect(jw, timestamp.getSeconds(), timestamp.getNanos());
		else
			WellKnownTypes.write(jw, message, writer);
	}

	static void writeDurationValue(JSONWriter jw, Message message, ProtobufMessageWriter writer) {
		if (message instanceof Duration duration)
			WellKnownTypes.writeDurationDirect(jw, duration.getSeconds(), duration.getNanos());
		else
			WellKnownTypes.write(jw, message, writer);
	}

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
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer, boolean utf8) {
			int v = getter.applyAsInt(msg);
			if (v == 0)
				return;
			name.writeTo(jw, utf8);
			if (unsigned)
				jw.writeInt64(Integer.toUnsignedLong(v));
			else
				jw.writeInt32(v);
		}
	}

	record LongAccessor(ToLongFunction<Message> getter, boolean unsigned,
			FieldName name) implements TypedFieldAccessor {
		@Override
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer, boolean utf8) {
			long v = getter.applyAsLong(msg);
			if (v == 0L)
				return;
			name.writeTo(jw, utf8);
			if (unsigned)
				WellKnownTypes.writeUnsignedLongString(jw, v);
			else
				jw.writeString(v);
		}
	}

	record FloatAccessor(ToDoubleFunction<Message> getter, FieldName name) implements TypedFieldAccessor {
		@Override
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer, boolean utf8) {
			float v = (float) getter.applyAsDouble(msg);
			if (Float.floatToRawIntBits(v) == 0)
				return;
			name.writeTo(jw, utf8);
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
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer, boolean utf8) {
			double v = getter.applyAsDouble(msg);
			if (Double.doubleToRawLongBits(v) == 0)
				return;
			name.writeTo(jw, utf8);
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
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer, boolean utf8) {
			if (getter.test(msg)) {
				name.writeTo(jw, utf8);
				jw.writeBool(true);
			}
		}
	}

	record StringAccessor(Function<Message, String> getter, FieldName name) implements TypedFieldAccessor {
		@Override
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer, boolean utf8) {
			String v = getter.apply(msg);
			if (v.isEmpty())
				return;
			name.writeTo(jw, utf8);
			jw.writeString(v);
		}
	}

	record ByteStringAccessor(Function<Message, ByteString> getter, FieldName name) implements TypedFieldAccessor {
		@Override
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer, boolean utf8) {
			ByteString v = getter.apply(msg);
			if (v.isEmpty())
				return;
			name.writeTo(jw, utf8);
			jw.writeBase64(v.toByteArray());
		}
	}

	record EnumAccessor(ToIntFunction<Message> valueGetter, String[] names, EnumDescriptor enumType, boolean nullValue,
			FieldName name) implements TypedFieldAccessor {
		@Override
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer, boolean utf8) {
			int ev = valueGetter.applyAsInt(msg);
			if (ev == 0)
				return;
			name.writeTo(jw, utf8);
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
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer, boolean utf8) {
			if (!has.test(msg))
				return;
			name.writeTo(jw, utf8);
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
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer, boolean utf8) {
			if (!has.test(msg))
				return;
			name.writeTo(jw, utf8);
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
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer, boolean utf8) {
			if (!has.test(msg))
				return;
			name.writeTo(jw, utf8);
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
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer, boolean utf8) {
			if (!has.test(msg))
				return;
			name.writeTo(jw, utf8);
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
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer, boolean utf8) {
			if (!has.test(msg))
				return;
			name.writeTo(jw, utf8);
			jw.writeBool(getter.test(msg));
		}
	}

	record PresenceStringAccessor(Function<Message, String> getter, Predicate<Message> has,
			FieldName name) implements TypedFieldAccessor {
		@Override
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer, boolean utf8) {
			if (!has.test(msg))
				return;
			name.writeTo(jw, utf8);
			jw.writeString(getter.apply(msg));
		}
	}

	record PresenceByteStringAccessor(Function<Message, ByteString> getter, Predicate<Message> has,
			FieldName name) implements TypedFieldAccessor {
		@Override
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer, boolean utf8) {
			if (!has.test(msg))
				return;
			name.writeTo(jw, utf8);
			jw.writeBase64(getter.apply(msg).toByteArray());
		}
	}

	record PresenceEnumAccessor(ToIntFunction<Message> valueGetter, Predicate<Message> has, String[] names,
			EnumDescriptor enumType, boolean nullValue, FieldName name) implements TypedFieldAccessor {
		@Override
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer, boolean utf8) {
			if (!has.test(msg))
				return;
			name.writeTo(jw, utf8);
			if (nullValue) {
				jw.writeNull();
				return;
			}
			writeEnumName(jw, valueGetter.applyAsInt(msg), names, enumType);
		}
	}

	record PresenceMessageAccessor(Function<Message, Message> getter, Predicate<Message> has, boolean wellKnown,
			FieldName name) implements TypedFieldAccessor {
		@Override
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer, boolean utf8) {
			if (!has.test(msg))
				return;
			name.writeTo(jw, utf8);
			Message nested = getter.apply(msg);
			if (wellKnown)
				WellKnownTypes.write(jw, nested, writer);
			else
				writer.writeMessage(jw, nested);
		}
	}

	record PresenceTimestampAccessor(Function<Message, Message> getter, Predicate<Message> has,
			FieldName name) implements TypedFieldAccessor {
		@Override
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer, boolean utf8) {
			if (!has.test(msg))
				return;
			name.writeTo(jw, utf8);
			writeTimestampValue(jw, getter.apply(msg), writer);
		}
	}

	record PresenceDurationAccessor(Function<Message, Message> getter, Predicate<Message> has,
			FieldName name) implements TypedFieldAccessor {
		@Override
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer, boolean utf8) {
			if (!has.test(msg))
				return;
			name.writeTo(jw, utf8);
			writeDurationValue(jw, getter.apply(msg), writer);
		}
	}

	// --- Repeated fields ---

	record RepeatedAccessor(Function<Message, List<?>> listGetter, FieldDescriptor fd,
			FieldName name) implements TypedFieldAccessor {
		@Override
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer, boolean utf8) {
			List<?> values = listGetter.apply(msg);
			if (values.isEmpty())
				return;
			name.writeTo(jw, utf8);
			FieldWriter.writeRepeated(jw, fd, values, writer);
		}
	}

	@SuppressWarnings("unchecked")
	record RepeatedIntAccessor(Function<Message, List<?>> listGetter, boolean unsigned,
			FieldName name) implements TypedFieldAccessor {
		@Override
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer, boolean utf8) {
			List<Integer> values = (List<Integer>) (List<?>) listGetter.apply(msg);
			if (values.isEmpty())
				return;
			name.writeTo(jw, utf8);
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
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer, boolean utf8) {
			List<Long> values = (List<Long>) (List<?>) listGetter.apply(msg);
			if (values.isEmpty())
				return;
			name.writeTo(jw, utf8);
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
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer, boolean utf8) {
			List<String> values = (List<String>) (List<?>) listGetter.apply(msg);
			if (values.isEmpty())
				return;
			name.writeTo(jw, utf8);
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
	record RepeatedMessageAccessor(Function<Message, List<?>> listGetter, boolean wellKnown,
			FieldName name) implements TypedFieldAccessor {
		@Override
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer, boolean utf8) {
			List<Message> values = (List<Message>) (List<?>) listGetter.apply(msg);
			if (values.isEmpty())
				return;
			name.writeTo(jw, utf8);
			jw.startArray();
			for (int i = 0; i < values.size(); i++) {
				if (i > 0)
					jw.writeComma();
				Message nested = values.get(i);
				if (wellKnown)
					WellKnownTypes.write(jw, nested, writer);
				else
					writer.writeMessage(jw, nested);
			}
			jw.endArray();
		}
	}

	@SuppressWarnings("unchecked")
	record RepeatedTimestampAccessor(Function<Message, List<?>> listGetter, FieldName name)
			implements TypedFieldAccessor {
		@Override
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer, boolean utf8) {
			List<Message> values = (List<Message>) (List<?>) listGetter.apply(msg);
			if (values.isEmpty())
				return;
			name.writeTo(jw, utf8);
			jw.startArray();
			for (int i = 0; i < values.size(); i++) {
				if (i > 0)
					jw.writeComma();
				writeTimestampValue(jw, values.get(i), writer);
			}
			jw.endArray();
		}
	}

	@SuppressWarnings("unchecked")
	record RepeatedDurationAccessor(Function<Message, List<?>> listGetter, FieldName name)
			implements TypedFieldAccessor {
		@Override
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer, boolean utf8) {
			List<Message> values = (List<Message>) (List<?>) listGetter.apply(msg);
			if (values.isEmpty())
				return;
			name.writeTo(jw, utf8);
			jw.startArray();
			for (int i = 0; i < values.size(); i++) {
				if (i > 0)
					jw.writeComma();
				writeDurationValue(jw, values.get(i), writer);
			}
			jw.endArray();
		}
	}

	record RepeatedEnumAccessor(Function<Message, List<?>> valueListGetter, String[] names, EnumDescriptor enumType,
			boolean nullValue, FieldName name) implements TypedFieldAccessor {
		@Override
		@SuppressWarnings("unchecked")
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer, boolean utf8) {
			List<Integer> values = (List<Integer>) (List<?>) valueListGetter.apply(msg);
			if (values.isEmpty())
				return;
			name.writeTo(jw, utf8);
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

	record MapAccessor(Function<Message, List<?>> entriesGetter, FieldDescriptor mapKeyDescriptor,
			FieldDescriptor mapValueDescriptor, FieldName name) implements TypedFieldAccessor {
		@Override
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer, boolean utf8) {
			List<?> entries = entriesGetter.apply(msg);
			if (entries.isEmpty())
				return;
			name.writeTo(jw, utf8);
			FieldWriter.writeMap(jw, mapKeyDescriptor, mapValueDescriptor, entries, writer);
		}
	}

	record TypedMapAccessor(Function<Message, java.util.Map<?, ?>> mapGetter, FieldDescriptor keyFd,
			FieldDescriptor valueFd, FieldName name) implements TypedFieldAccessor {
		@Override
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer, boolean utf8) {
			java.util.Map<?, ?> map = mapGetter.apply(msg);
			if (map.isEmpty())
				return;
			name.writeTo(jw, utf8);
			jw.startObject();
			boolean first = true;
			for (var entry : map.entrySet()) {
				if (first)
					first = false;
				else
					jw.writeComma();
				FieldWriter.writeMapKey(jw, keyFd, entry.getKey());
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
		public void write(JSONWriter jw, Message msg, ProtobufMessageWriter writer, boolean utf8) {
			FieldDescriptor setField = msg.getOneofFieldDescriptor(oneof);
			if (setField == null)
				return;
			int number = setField.getNumber();
			for (int i = 0; i < fieldNumbers.length; i++) {
				if (fieldNumbers[i] == number) {
					accessors[i].write(jw, msg, writer, utf8);
					return;
				}
			}
		}
	}
}
