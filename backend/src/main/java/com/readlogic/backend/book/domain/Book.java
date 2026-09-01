package com.readlogic.backend.book.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "books")
public class Book {

	@Id
	private UUID id;

	@Column(nullable = false)
	private String title;

	private String author;

	@OneToMany(mappedBy = "book", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
	@OrderBy("pageNumber ASC")
	private final List<BookPage> pages = new ArrayList<>();

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Book() {
	}

	public Book(String title, String author) {
		this.id = UUID.randomUUID();
		this.title = title;
		this.author = author;
	}

	@PrePersist
	void onCreate() {
		Instant now = Instant.now();
		createdAt = now;
		updatedAt = now;
	}

	@PreUpdate
	void onUpdate() {
		updatedAt = Instant.now();
	}

	public void updateMetadata(String title, String author) {
		this.title = title;
		this.author = author;
	}

	public void addPage(BookPage page) {
		pages.add(page);
		page.attachTo(this);
		touch();
	}

	public void removePage(BookPage page) {
		pages.remove(page);
		page.detach();
		touch();
	}

	public void touch() {
		updatedAt = Instant.now();
	}

	public UUID getId() {
		return id;
	}

	public String getTitle() {
		return title;
	}

	public String getAuthor() {
		return author;
	}

	public List<BookPage> getPages() {
		return Collections.unmodifiableList(pages);
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
