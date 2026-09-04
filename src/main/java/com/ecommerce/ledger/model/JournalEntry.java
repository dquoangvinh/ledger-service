package com.ecommerce.ledger.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** One balanced transaction: a set of postings whose debits equal their credits. */
@Entity
@Table(name = "ledger_journal_entry")
public class JournalEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 64)
    private String idempotencyKey;

    @Column(length = 255)
    private String description;

    /** Set when this entry reverses another one. NULL for ordinary entries. */
    @Column(name = "reverses_id")
    private Long reversesId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @OneToMany(mappedBy = "entry", cascade = CascadeType.PERSIST)
    private List<Posting> postings = new ArrayList<>();

    protected JournalEntry() {
    }

    public JournalEntry(String idempotencyKey, String description, Long reversesId) {
        this.idempotencyKey = idempotencyKey;
        this.description = description;
        this.reversesId = reversesId;
    }

    public Long getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Long getReversesId() {
        return reversesId;
    }

    public List<Posting> getPostings() {
        return postings;
    }

    public void addPosting(Posting posting) {
        postings.add(posting);
        posting.setEntry(this);
    }
}
