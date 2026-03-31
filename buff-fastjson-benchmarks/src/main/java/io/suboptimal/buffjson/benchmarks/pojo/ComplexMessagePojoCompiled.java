package io.suboptimal.buffjson.benchmarks.pojo;

import java.util.List;
import java.util.Map;

public class ComplexMessagePojoCompiled {

	private String id;
	private String name;
	private int version;
	private AddressPojoCompiled primaryAddress;
	private List<String> tagsList;
	private List<AddressPojoCompiled> addresses;
	private List<TagPojoCompiled> tags;
	private Map<String, String> metadata;
	private Map<Integer, AddressPojoCompiled> addressBook;
	private String email;
	private String payload;
	private String createdAt;
	private String updatedAt;
	private String status;

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public int getVersion() {
		return version;
	}

	public void setVersion(int version) {
		this.version = version;
	}

	public AddressPojoCompiled getPrimaryAddress() {
		return primaryAddress;
	}

	public void setPrimaryAddress(AddressPojoCompiled primaryAddress) {
		this.primaryAddress = primaryAddress;
	}

	public List<String> getTagsList() {
		return tagsList;
	}

	public void setTagsList(List<String> tagsList) {
		this.tagsList = tagsList;
	}

	public List<AddressPojoCompiled> getAddresses() {
		return addresses;
	}

	public void setAddresses(List<AddressPojoCompiled> addresses) {
		this.addresses = addresses;
	}

	public List<TagPojoCompiled> getTags() {
		return tags;
	}

	public void setTags(List<TagPojoCompiled> tags) {
		this.tags = tags;
	}

	public Map<String, String> getMetadata() {
		return metadata;
	}

	public void setMetadata(Map<String, String> metadata) {
		this.metadata = metadata;
	}

	public Map<Integer, AddressPojoCompiled> getAddressBook() {
		return addressBook;
	}

	public void setAddressBook(Map<Integer, AddressPojoCompiled> addressBook) {
		this.addressBook = addressBook;
	}

	public String getEmail() {
		return email;
	}

	public void setEmail(String email) {
		this.email = email;
	}

	public String getPayload() {
		return payload;
	}

	public void setPayload(String payload) {
		this.payload = payload;
	}

	public String getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(String createdAt) {
		this.createdAt = createdAt;
	}

	public String getUpdatedAt() {
		return updatedAt;
	}

	public void setUpdatedAt(String updatedAt) {
		this.updatedAt = updatedAt;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}
}
