package io.suboptimal.buffjson.internal;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.alibaba.fastjson2.JSONWriter;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.TypeRegistry;

import io.suboptimal.buffjson.BuffJSON;

/**
 * Specialized JSON serialization for protobuf
 * <a href="https://protobuf.dev/reference/protobuf/google.protobuf/">well-known
 * types</a>.
 *
 * <p>
 * These types have special JSON representations defined by the proto3 spec that
 * differ from their standard message serialization. Supports 15 types:
 *
 * <ul>
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

	public static void write(JSONWriter jsonWriter, Message message) {
		var descriptor = message.getDescriptorForType();
		switch (descriptor.getFullName()) {
			case "google.protobuf.Any" -> writeAny(jsonWriter, message);
			case "google.protobuf.Timestamp" -> writeTimestamp(jsonWriter, message);
			case "google.protobuf.Duration" -> writeDuration(jsonWriter, message);
			case "google.protobuf.FieldMask" -> writeFieldMask(jsonWriter, message);
			case "google.protobuf.Struct" -> writeStruct(jsonWriter, message);
			case "google.protobuf.Value" -> writeValue(jsonWriter, message);
			case "google.protobuf.ListValue" -> writeListValue(jsonWriter, message);
			case "google.protobuf.DoubleValue", "google.protobuf.FloatValue", "google.protobuf.Int64Value",
					"google.protobuf.UInt64Value", "google.protobuf.Int32Value", "google.protobuf.UInt32Value",
					"google.protobuf.BoolValue", "google.protobuf.StringValue", "google.protobuf.BytesValue" ->
				writeWrapper(jsonWriter, message);
			default -> throw new IllegalArgumentException("Unknown well-known type: " + descriptor.getFullName());
		}
	}

	private static void writeAny(JSONWriter jsonWriter, Message message) {
		var fields = getFields(message, "type_url", "value");
		String typeUrl = (String) message.getField(fields[0]);
		ByteString content = (ByteString) message.getField(fields[1]);

		// Default Any (empty type_url and value) → empty object
		if (typeUrl.isEmpty() && content.isEmpty()) {
			jsonWriter.startObject();
			jsonWriter.endObject();
			return;
		}

		TypeRegistry registry = BuffJSON.ACTIVE_REGISTRY.get();
		if (registry == null) {
			throw new IllegalStateException("Cannot serialize google.protobuf.Any without a TypeRegistry. "
					+ "Use BuffJSON.encoder().withTypeRegistry(registry).encode(message).");
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
			write(jsonWriter, contentMessage);
		} else {
			// Regular message: {"@type": "...", ...fields...}
			ProtobufMessageWriter.writeFields(jsonWriter, contentMessage);
		}

		jsonWriter.endObject();
	}

	private static void writeTimestamp(JSONWriter jsonWriter, Message message) {
		var fields = getFields(message, "seconds", "nanos");
		long seconds = (long) message.getField(fields[0]);
		int nanos = (int) message.getField(fields[1]);

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

	private static void writeStruct(JSONWriter jsonWriter, Message message) {
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
			writeValue(jsonWriter, value);
		}
		jsonWriter.endObject();
	}

	private static void writeValue(JSONWriter jsonWriter, Message message) {
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
			case "struct_value" -> writeStruct(jsonWriter, (Message) message.getField(activeField));
			case "list_value" -> writeListValue(jsonWriter, (Message) message.getField(activeField));
		}
	}

	private static void writeListValue(JSONWriter jsonWriter, Message message) {
		var fields = getFields(message, "values");
		@SuppressWarnings("unchecked")
		List<Message> values = (List<Message>) message.getField(fields[0]);

		jsonWriter.startArray();
		for (int i = 0; i < values.size(); i++) {
			if (i > 0)
				jsonWriter.writeComma();
			writeValue(jsonWriter, values.get(i));
		}
		jsonWriter.endArray();
	}

	private static void writeWrapper(JSONWriter jsonWriter, Message message) {
		// Wrapper types have a single "value" field — delegate to FieldWriter
		// which already handles unsigned formatting, NaN/Infinity, Base64, etc.
		var fields = getFields(message, "value");
		FieldWriter.writeValue(jsonWriter, fields[0], message.getField(fields[0]));
	}

	/**
	 * Looks up and caches field descriptors for a well-known type message by name.
	 * Avoids repeated {@code findFieldByName()} calls on the hot path.
	 */
	private static FieldDescriptor[] getFields(Message message, String... names) {
		var desc = message.getDescriptorForType();
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
}
