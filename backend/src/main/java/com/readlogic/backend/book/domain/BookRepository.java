package com.readlogic.backend.book.domain;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookRepository extends JpaRepository<Book, UUID> {

	@EntityGraph(attributePaths = "pages")
	@Query("select distinct b from Book b where b.id = :id")
	Optional<Book> findWithPagesById(@Param("id") UUID id);

	@EntityGraph(attributePaths = "pages")
	List<Book> findAllByOrderByCreatedAtDesc();
}

