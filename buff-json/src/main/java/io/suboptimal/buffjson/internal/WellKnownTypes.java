package io.suboptimal.buffjson.internal;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.JSONWriter;
import com.google.protobuf.*;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;

/**
 * Specialized JSON serialization for protobuf
 * <a href="https://protobuf.dev/reference/protobuf/google.protobuf/">well-known
 * types</a>.
 *
 * <p>
 * These types have special JSON representations defined by the proto3 spec that
 * differ from their standard message serialization. Supports 16 types:
 *
 * <ul>
 * <li><b>Any</b>: {@code {"@type": "type.googleapis.com/...", ...}} with
 * TypeRegistry resolution
 * <li><b>Timestamp</b>: RFC 3339 string ({@code "2024-01-01T00:00:00Z"})
 * <li><b>Duration</b>: seconds string with 's' suffix ({@code "3600.500s"})
 * <li><b>FieldMask</b>: comma-separated camelCase paths ({@code "foo,barBaz"})
 * <li><b>Struct</b>: native JSON object
 * <li><b>Value</b>: native JSON value (string, number, bool, null, object, or
 * array)
 * <li><b>ListValue</b>: native JSON array
 * <li><b>Wrappers</b> (Int32Value, Int64Value, UInt32Value, UInt64Value,
 * FloatValue, DoubleValue, BoolValue, StringValue, BytesValue): unwrapped to
 * primitive JSON values
 * </ul>
 *
 * <p>
 * Nanos formatting uses 3, 6, or 9 digits (matching protobuf's convention of
 * grouping into millis, micros, or nanos — never arbitrary precision).
 *
 * <p>
 * For Timestamp and Duration, {@code writeTimestampDirect()} and
 * {@code writeDurationDirect()} accept primitive seconds/nanos directly,
 * bypassing descriptor lookup and {@code message.getField()} reflection. These
 * are used by generated encoders that know the field type at generation time.
 */
public final class WellKnownTypes {

	private static final Set<String> WELL_KNOWN_TYPE_NAMES = Set.of("google.protobuf.Any", "google.protobuf.Timestamp",
			"google.protobuf.Duration", "google.protobuf.FieldMask", "google.protobuf.Struct", "google.protobuf.Value",
			"google.protobuf.ListValue", "google.protobuf.DoubleValue", "google.protobuf.FloatValue",
			"google.protobuf.Int64Value", "google.protobuf.UInt64Value", "google.protobuf.Int32Value",
			"google.protobuf.UInt32Value", "google.protobuf.BoolValue", "google.protobuf.StringValue",
			"google.protobuf.BytesValue");

	private static final DateTimeFormatter RFC3339 = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
			.withZone(ZoneOffset.UTC);

	static final Base64.Encoder BASE64 = Base64.getEncoder();

	/**
	 * Cached field descriptors for well-known types to avoid repeated
	 * findFieldByName lookups.
	 */
	private static final ConcurrentHashMap<Descriptor, FieldDescriptor[]> WKT_FIELD_CACHE = new ConcurrentHashMap<>();

	private WellKnownTypes() {
	}

	public static boolean isWellKnownType(Descriptor descriptor) {
		return WELL_KNOWN_TYPE_NAMES.contains(descriptor.getFullName());
	}

	public static void write(JSONWriter jsonWriter, Message message, ProtobufMessageWriter writer) {
		var descriptor = message.getDescriptorForType();
		switch (descriptor.getFullName()) {
			case "google.protobuf.Any" -> writeAny(jsonWriter, message, writer);
			case "google.protobuf.Timestamp" -> writeTimestamp(jsonWriter, message);
			case "google.protobuf.Duration" -> writeDuration(jsonWriter, message);
			case "google.protobuf.FieldMask" -> writeFieldMask(jsonWriter, message);
			case "google.protobuf.Struct" -> writeStruct(jsonWriter, message, writer);
			case "google.protobuf.Value" -> writeValue(jsonWriter, message, writer);
			case "google.protobuf.ListValue" -> writeListValue(jsonWriter, message, writer);
			case "google.protobuf.DoubleValue", "google.protobuf.FloatValue", "google.protobuf.Int64Value",
					"google.protobuf.UInt64Value", "google.protobuf.Int32Value", "google.protobuf.UInt32Value",
					"google.protobuf.BoolValue", "google.protobuf.StringValue", "google.protobuf.BytesValue" ->
				writeWrapper(jsonWriter, message, writer);
			default -> throw new IllegalArgumentException("Unknown well-known type: " + descriptor.getFullName());
		}
	}

	private static void writeAny(JSONWriter jsonWriter, Message message, ProtobufMessageWriter writer) {
		var fields = getFields(message, "type_url", "value");
		String typeUrl = (String) message.getField(fields[0]);
		ByteString content = (ByteString) message.getField(fields[1]);

		// Default Any (empty type_url and value) → empty object
		if (typeUrl.isEmpty() && content.isEmpty()) {
			jsonWriter.startObject();
			jsonWriter.endObject();
			return;
		}

		TypeRegistry registry = writer.typeRegistry();
		if (registry == null) {
			throw new IllegalStateException("Cannot serialize google.protobuf.Any without a TypeRegistry. "
					+ "Use BuffJson.encoder().setTypeRegistry(registry).encode(message).");
		}

		Descriptor type;
		try {
			type = registry.getDescriptorForTypeUrl(typeUrl);
		} catch (InvalidProtocolBufferException e) {
			throw new IllegalStateException("Invalid type URL in Any: " + typeUrl, e);
		}
		if (type == null) {
			throw new IllegalStateException("Cannot find type for url: " + typeUrl
					+ ". Register it via TypeRegistry.newBuilder().add(descriptor).build().");
		}

		Message contentMessage;
		try {
			contentMessage = DynamicMessage.parseFrom(type, content);
		} catch (InvalidProtocolBufferException e) {
			throw new IllegalStateException("Failed to parse Any content for type: " + typeUrl, e);
		}

		jsonWriter.startObject();
		jsonWriter.writeName("@type");
		jsonWriter.writeColon();
		jsonWriter.writeString(typeUrl);

		if (isWellKnownType(type)) {
			// WKT packed in Any: {"@type": "...", "value": <wkt-json>}
			jsonWriter.writeName("value");
			jsonWriter.writeColon();
			write(jsonWriter, contentMessage, writer);
		} else {
			// Regular message: {"@type": "...", ...fields...}
			writer.writeFields(jsonWriter, contentMessage);
		}

		jsonWriter.endObject();
	}

	private static void writeTimestamp(JSONWriter jsonWriter, Message message) {
		var fields = getFields(message, "seconds", "nanos");
		long seconds = (long) message.getField(fields[0]);
		int nanos = (int) message.getField(fields[1]);
		writeTimestampDirect(jsonWriter, seconds, nanos);
	}

	/**
	 * Writes a Timestamp directly from typed seconds/nanos, bypassing descriptor
	 * lookup and reflection. Used by generated encoders that know the field type at
	 * generation time.
	 */
	public static void writeTimestampDirect(JSONWriter jsonWriter, long seconds, int nanos) {
		Instant instant = Instant.ofEpochSecond(seconds, nanos);
		StringBuilder sb = new StringBuilder(30);
		RFC3339.formatTo(instant, sb);
		if (nanos == 0) {
			sb.append('Z');
		} else {
			sb.append('.');
			appendNanos(sb, nanos);
			sb.append('Z');
		}
		jsonWriter.writeString(sb.toString());
	}

	private static void writeDuration(JSONWriter jsonWriter, Message message) {
		var fields = getFields(message, "seconds", "nanos");
		long seconds = (long) message.getField(fields[0]);
		int nanos = (int) message.getField(fields[1]);
		writeDurationDirect(jsonWriter, seconds, nanos);
	}

	/**
	 * Writes a Duration directly from typed seconds/nanos, bypassing descriptor
	 * lookup and reflection. Used by generated encoders that know the field type at
	 * generation time.
	 */
	public static void writeDurationDirect(JSONWriter jsonWriter, long seconds, int nanos) {
		StringBuilder sb = new StringBuilder(20);
		if (seconds < 0 || nanos < 0) {
			sb.append('-');
			seconds = Math.abs(seconds);
			nanos = Math.abs(nanos);
		}
		sb.append(seconds);
		if (nanos != 0) {
			sb.append('.');
			appendNanos(sb, nanos);
		}
		sb.append('s');
		jsonWriter.writeString(sb.toString());
	}

	private static void writeFieldMask(JSONWriter jsonWriter, Message message) {
		var fields = getFields(message, "paths");
		@SuppressWarnings("unchecked")
		List<String> paths = (List<String>) message.getField(fields[0]);

		StringBuilder sb = new StringBuilder(paths.size() * 16);
		for (int i = 0; i < paths.size(); i++) {
			if (i > 0)
				sb.append(',');
			sb.append(snakeToCamel(paths.get(i)));
		}
		jsonWriter.writeString(sb.toString());
	}

	private static void writeStruct(JSONWriter jsonWriter, Message message, ProtobufMessageWriter writer) {
		var fields = getFields(message, "fields");
		@SuppressWarnings("unchecked")
		List<Message> entries = (List<Message>) message.getField(fields[0]);

		jsonWriter.startObject();
		for (var entry : entries) {
			var entryFields = getFields(entry, "key", "value");
			String key = (String) entry.getField(entryFields[0]);
			Message value = (Message) entry.getField(entryFields[1]);
			jsonWriter.writeName(key);
			jsonWriter.writeColon();
			writeValue(jsonWriter, value, writer);
		}
		jsonWriter.endObject();
	}

	private static void writeValue(JSONWriter jsonWriter, Message message, ProtobufMessageWriter writer) {
		var desc = message.getDescriptorForType();
		var kindOneof = desc.getOneofs().get(0);
		var activeField = message.getOneofFieldDescriptor(kindOneof);

		if (activeField == null) {
			jsonWriter.writeNull();
			return;
		}

		switch (activeField.getName()) {
			case "null_value" -> jsonWriter.writeNull();
			case "number_value" -> jsonWriter.writeDouble((double) message.getField(activeField));
			case "string_value" -> jsonWriter.writeString((String) message.getField(activeField));
			case "bool_value" -> jsonWriter.writeBool((boolean) message.getField(activeField));
			case "struct_value" -> writeStruct(jsonWriter, (Message) message.getField(activeField), writer);
			case "list_value" -> writeListValue(jsonWriter, (Message) message.getField(activeField), writer);
		}
	}

	private static void writeListValue(JSONWriter jsonWriter, Message message, ProtobufMessageWriter writer) {
		var fields = getFields(message, "values");
		@SuppressWarnings("unchecked")
		List<Message> values = (List<Message>) message.getField(fields[0]);

		jsonWriter.startArray();
		for (int i = 0; i < values.size(); i++) {
			if (i > 0)
				jsonWriter.writeComma();
			writeValue(jsonWriter, values.get(i), writer);
		}
		jsonWriter.endArray();
	}

	private static void writeWrapper(JSONWriter jsonWriter, Message message, ProtobufMessageWriter writer) {
		// Wrapper types have a single "value" field — delegate to FieldWriter
		// which already handles unsigned formatting, NaN/Infinity, Base64, etc.
		var fields = getFields(message, "value");
		FieldWriter.writeValue(jsonWriter, fields[0], message.getField(fields[0]), writer);
	}

	/**
	 * Looks up and caches field descriptors for a well-known type message by name.
	 * Avoids repeated {@code findFieldByName()} calls on the hot path.
	 *
	 * <p>
	 * Each WKT Descriptor must always be called with the same {@code names} — the
	 * cache is keyed by Descriptor alone.
	 */
	private static FieldDescriptor[] getFields(Message message, String... names) {
		var desc = message.getDescriptorForType();
		// Fast path: avoid varargs-driven computeIfAbsent on cache hit
		var cached = WKT_FIELD_CACHE.get(desc);
		if (cached != null) {
			return cached;
		}
		return WKT_FIELD_CACHE.computeIfAbsent(desc, d -> {
			FieldDescriptor[] result = new FieldDescriptor[names.length];
			for (int i = 0; i < names.length; i++) {
				result[i] = d.findFieldByName(names[i]);
			}
			return result;
		});
	}

	/**
	 * Appends nanos as 3, 6, or 9 digits to the StringBuilder. Protobuf convention:
	 * use minimum group size (millis/micros/nanos) to represent the value.
	 */
	private static void appendNanos(StringBuilder sb, int nanos) {
		if (nanos % 1_000_000 == 0) {
			int millis = nanos / 1_000_000;
			if (millis < 10)
				sb.append("00");
			else if (millis < 100)
				sb.append('0');
			sb.append(millis);
		} else if (nanos % 1_000 == 0) {
			int micros = nanos / 1_000;
			if (micros < 10)
				sb.append("00000");
			else if (micros < 100)
				sb.append("0000");
			else if (micros < 1000)
				sb.append("000");
			else if (micros < 10000)
				sb.append("00");
			else if (micros < 100000)
				sb.append('0');
			sb.append(micros);
		} else {
			if (nanos < 10)
				sb.append("00000000");
			else if (nanos < 100)
				sb.append("0000000");
			else if (nanos < 1000)
				sb.append("000000");
			else if (nanos < 10000)
				sb.append("00000");
			else if (nanos < 100000)
				sb.append("0000");
			else if (nanos < 1000000)
				sb.append("000");
			else if (nanos < 10000000)
				sb.append("00");
			else if (nanos < 100000000)
				sb.append('0');
			sb.append(nanos);
		}
	}

	/**
	 * Converts snake_case to lowerCamelCase for FieldMask paths per proto3 JSON
	 * spec.
	 */
	private static String snakeToCamel(String snake) {
		StringBuilder sb = new StringBuilder(snake.length());
		boolean upperNext = false;
		for (int i = 0; i < snake.length(); i++) {
			char c = snake.charAt(i);
			if (c == '_') {
				upperNext = true;
			} else {
				sb.append(upperNext ? Character.toUpperCase(c) : c);
				upperNext = false;
			}
		}
		return sb.toString();
	}

	// =========================================================================
	// Deserialization (JSON → protobuf)
	// =========================================================================

	/**
	 * Reads a well-known type from JSON. Dispatches by descriptor full name.
	 */
	public static Message readWkt(JSONReader reader, Descriptor descriptor, ProtobufMessageReader msgReader) {
		return switch (descriptor.getFullName()) {
			case "google.protobuf.Any" -> readAny(reader, msgReader);
			case "google.protobuf.Timestamp" -> readTimestamp(reader);
			case "google.protobuf.Duration" -> readDuration(reader);
			case "google.protobuf.FieldMask" -> readFieldMask(reader);
			case "google.protobuf.Struct" -> readStruct(reader);
			case "google.protobuf.Value" -> readJsonValue(reader);
			case "google.protobuf.ListValue" -> readListValue(reader);
			case "google.protobuf.DoubleValue" -> DoubleValue.of(FieldReader.readDoubleValue(reader));
			case "google.protobuf.FloatValue" -> FloatValue.of(FieldReader.readFloatValue(reader));
			case "google.protobuf.Int64Value" -> Int64Value.of(FieldReader.readSignedLong(reader));
			case "google.protobuf.UInt64Value" -> UInt64Value.of(FieldReader.readUnsignedLong(reader));
			case "google.protobuf.Int32Value" -> Int32Value.of(reader.readInt32Value());
			case "google.protobuf.UInt32Value" -> UInt32Value.of((int) reader.readInt64Value());
			case "google.protobuf.BoolValue" -> BoolValue.of(reader.readBoolValue());
			case "google.protobuf.StringValue" -> StringValue.of(reader.readString());
			case "google.protobuf.BytesValue" ->
				BytesValue.of(ByteString.copyFrom(FieldReader.BASE64.decode(reader.readString())));
			default -> throw new IllegalArgumentException("Unknown well-known type: " + descriptor.getFullName());
		};
	}

	/** Reads an RFC 3339 timestamp string and returns a Timestamp message. */
	public static Timestamp readTimestamp(JSONReader reader) {
		String rfc3339 = reader.readString();
		return parseTimestamp(rfc3339);
	}

	/**
	 * Reads a duration string (e.g., "3600.500s") and returns a Duration message.
	 */
	public static Duration readDuration(JSONReader reader) {
		String s = reader.readString();
		return parseDuration(s);
	}

	static Timestamp parseTimestamp(String rfc3339) {
		Instant instant = Instant.parse(rfc3339);
		return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
	}

	static Duration parseDuration(String s) {
		if (!s.endsWith("s")) {
			throw new IllegalArgumentException("Invalid duration: " + s);
		}
		String numPart = s.substring(0, s.length() - 1);
		boolean negative = numPart.startsWith("-");
		if (negative) {
			numPart = numPart.substring(1);
		}

		long seconds;
		int nanos = 0;
		int dotIndex = numPart.indexOf('.');
		if (dotIndex >= 0) {
			seconds = Long.parseLong(numPart.substring(0, dotIndex));
			String fracStr = numPart.substring(dotIndex + 1);
			// Pad to 9 digits
			fracStr = (fracStr + "000000000").substring(0, 9);
			nanos = Integer.parseInt(fracStr);
		} else {
			seconds = Long.parseLong(numPart);
		}

		if (negative) {
			seconds = -seconds;
			if (nanos != 0) {
				nanos = -nanos;
			}
		}
		return Duration.newBuilder().setSeconds(seconds).setNanos(nanos).build();
	}

	private static FieldMask readFieldMask(JSONReader reader) {
		String camelCase = reader.readString();
		FieldMask.Builder builder = FieldMask.newBuilder();
		if (!camelCase.isEmpty()) {
			for (String path : camelCase.split(",")) {
				builder.addPaths(camelToSnake(path));
			}
		}
		return builder.build();
	}

	/** Reads a native JSON object as a protobuf Struct. */
	public static Struct readStruct(JSONReader reader) {
		Struct.Builder builder = Struct.newBuilder();
		reader.nextIfObjectStart();
		while (!reader.nextIfObjectEnd()) {
			String key = reader.readFieldName();
			if (key == null) {
				break;
			}
			Value value = readJsonValueImpl(reader);
			builder.putFields(key, value);
		}
		return builder.build();
	}

	/** Reads a native JSON value as a protobuf Value (dispatch on token type). */
	public static Value readJsonValue(JSONReader reader) {
		return readJsonValueImpl(reader);
	}

	private static Value readJsonValueImpl(JSONReader reader) {
		if (reader.nextIfNull()) {
			return Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();
		}
		if (reader.isString()) {
			return Value.newBuilder().setStringValue(reader.readString()).build();
		}
		char c = reader.current();
		if (c == 't' || c == 'f') {
			return Value.newBuilder().setBoolValue(reader.readBoolValue()).build();
		}
		if (c == '{') {
			return Value.newBuilder().setStructValue(readStruct(reader)).build();
		}
		if (c == '[') {
			return Value.newBuilder().setListValue(readListValue(reader)).build();
		}
		// Must be a number
		return Value.newBuilder().setNumberValue(reader.readDoubleValue()).build();
	}

	/** Reads a native JSON array as a protobuf ListValue. */
	public static ListValue readListValue(JSONReader reader) {
		ListValue.Builder builder = ListValue.newBuilder();
		reader.nextIfArrayStart();
		while (!reader.nextIfArrayEnd()) {
			builder.addValues(readJsonValueImpl(reader));
		}
		return builder.build();
	}

	private static Any readAny(JSONReader reader, ProtobufMessageReader msgReader) {
		// Read the entire JSON object into a map to extract @type first
		reader.nextIfObjectStart();

		String typeUrl = null;
		Map<String, Object> allFields = new LinkedHashMap<>();
		while (!reader.nextIfObjectEnd()) {
			String key = reader.readFieldName();
			if (key == null) {
				break;
			}
			if ("@type".equals(key)) {
				typeUrl = reader.readString();
			} else {
				allFields.put(key, reader.readAny());
			}
		}

		if (typeUrl == null || typeUrl.isEmpty()) {
			return Any.getDefaultInstance();
		}

		TypeRegistry registry = msgReader.typeRegistry();
		if (registry == null) {
			throw new IllegalStateException("Cannot deserialize google.protobuf.Any without a TypeRegistry. "
					+ "Use BuffJson.decoder().setTypeRegistry(registry).decode(json, clazz).");
		}

		Descriptor type;
		try {
			type = registry.getDescriptorForTypeUrl(typeUrl);
		} catch (InvalidProtocolBufferException e) {
			throw new IllegalStateException("Invalid type URL in Any: " + typeUrl, e);
		}
		if (type == null) {
			throw new IllegalStateException("Cannot find type for url: " + typeUrl);
		}

		Message contentMessage;
		if (isWellKnownType(type)) {
			// WKT packed in Any: {"@type": "...", "value": <wkt-json>}
			Object valueObj = allFields.get("value");
			String valueJson = com.alibaba.fastjson2.JSON.toJSONString(valueObj);
			try (JSONReader valueReader = JSONReader.of(valueJson)) {
				contentMessage = readWkt(valueReader, type, msgReader);
			}
		} else {
			// Regular message: {"@type": "...", ...fields...}
			String fieldsJson = com.alibaba.fastjson2.JSON.toJSONString(allFields);
			try (JSONReader fieldsReader = JSONReader.of(fieldsJson)) {
				contentMessage = msgReader.readMessage(fieldsReader, type);
			}
		}

		return Any.pack(contentMessage);
	}

	/**
	 * Converts lowerCamelCase to snake_case for FieldMask path parsing.
	 */
	static String camelToSnake(String camel) {
		StringBuilder sb = new StringBuilder(camel.length() + 4);
		for (int i = 0; i < camel.length(); i++) {
			char c = camel.charAt(i);
			if (Character.isUpperCase(c)) {
				sb.append('_').append(Character.toLowerCase(c));
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}
}
