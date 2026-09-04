-- Guards on reversing entries.
--
-- Without these, one transaction can end up with four entries: reverse #1 -> #2,
-- then reverse the reversal #2 -> #3, then reverse #1 a second time -> #4. The
-- balances still come out right because the reversals cancel, but the ledger is
-- no longer readable, which defeats the point of keeping history.
--
-- Two of the three rules live here; the third (never reverse a reversal) is checked
-- in the service, because a constraint cannot read the table it is guarding.

-- An entry may be reversed at most once.
-- A partial index is required: PostgreSQL does not treat multiple NULLs as duplicates,
-- so ordinary entries (reverses_id IS NULL) are unaffected either way, but the WHERE
-- clause states the intent and keeps the index small.
CREATE UNIQUE INDEX uq_ledger_journal_entry_reverses
    ON ledger_journal_entry(reverses_id)
    WHERE reverses_id IS NOT NULL;

-- An entry cannot reverse itself.
ALTER TABLE ledger_journal_entry
    ADD CONSTRAINT chk_ledger_not_self_reversal
    CHECK (reverses_id IS NULL OR reverses_id <> id);
