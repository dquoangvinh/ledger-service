-- Double-entry ledger for payment-service (wallet / store credit).
--
-- Four invariants this schema exists to enforce:
--   I1  every journal entry has total debits = total credits   -> trg_ledger_entry_balance
--   I2  total money in the system never changes                -> checked by test, not enforceable in one table
--   I3  postings are never updated or deleted                  -> trg_ledger_posting_immutable
--   I4  one idempotency_key produces at most one entry         -> UNIQUE + service-side check
--
-- Tables are prefixed with "ledger_" because payment-service already owns other
-- aggregates (payments, outbox_events); the prefix keeps this module obvious.

CREATE TABLE ledger_account (
    id            BIGSERIAL PRIMARY KEY,
    account_no    VARCHAR(32) NOT NULL UNIQUE,
    -- Without account_type there is no way to know whether a debit raises or lowers
    -- the balance. A customer wallet is a LIABILITY, so a debit *decreases* it.
    account_type  VARCHAR(16) NOT NULL
        CHECK (account_type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    -- VARCHAR, not CHAR: CHAR(n) pads with spaces in PostgreSQL, which surprises
    -- string comparison and JSON output alike.
    currency      VARCHAR(3)  NOT NULL DEFAULT 'VND',
    status        VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    -- Owner of the account, used for object-level authorization (OWASP API1: BOLA).
    -- No foreign key: users live in another bounded context (user-service / Keycloak).
    -- NULL means a system account (platform cash, revenue, fees).
    owner_user_id UUID,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Ownership is looked up on every transfer, so it has to be indexed.
CREATE INDEX idx_ledger_account_owner ON ledger_account(owner_user_id);

-- Balance lives in its own table: it is updated constantly, and splitting it keeps
-- row locks off the account metadata that everyone else only reads.
CREATE TABLE ledger_account_balance (
    account_id BIGINT PRIMARY KEY REFERENCES ledger_account(id),
    -- NUMERIC, never DOUBLE. Adding 0.10 ten thousand times gives 1000.0000 with
    -- NUMERIC and 1000.0000000001588 with float8.
    balance    NUMERIC(20, 4) NOT NULL DEFAULT 0,
    version    BIGINT         NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE TABLE ledger_journal_entry (
    id              BIGSERIAL PRIMARY KEY,
    idempotency_key VARCHAR(64) NOT NULL UNIQUE,
    description     VARCHAR(255),
    -- Corrections are made by writing a reversing entry, never by editing history.
    reverses_id     BIGINT REFERENCES ledger_journal_entry(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ledger_posting (
    id         BIGSERIAL PRIMARY KEY,
    entry_id   BIGINT         NOT NULL REFERENCES ledger_journal_entry(id),
    account_id BIGINT         NOT NULL REFERENCES ledger_account(id),
    -- Direction is a column and the amount is always positive. Encoding debits as
    -- negative numbers would make every value valid and impossible to check.
    side       VARCHAR(1)     NOT NULL CHECK (side IN ('D', 'C')),
    amount     NUMERIC(20, 4) NOT NULL CHECK (amount > 0),
    created_at TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX idx_ledger_posting_account ON ledger_posting(account_id, created_at DESC);
CREATE INDEX idx_ledger_posting_entry ON ledger_posting(entry_id);

-- I3: postings are append-only, enforced in the database rather than trusted to
-- application code.
CREATE FUNCTION ledger_posting_immutable() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'ledger_posting is append-only: % not allowed', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_posting_immutable
    BEFORE UPDATE OR DELETE ON ledger_posting
    FOR EACH ROW EXECUTE FUNCTION ledger_posting_immutable();

-- I1: every entry must balance. This has to be a DEFERRABLE CONSTRAINT TRIGGER -
-- a normal trigger would fire on the first leg, when the entry cannot be balanced yet.
CREATE FUNCTION ledger_entry_must_balance() RETURNS TRIGGER AS $$
DECLARE
    diff NUMERIC(20, 4);
BEGIN
    SELECT COALESCE(SUM(CASE WHEN side = 'D' THEN amount ELSE -amount END), 0)
      INTO diff
      FROM ledger_posting
     WHERE entry_id = NEW.entry_id;

    IF diff <> 0 THEN
        RAISE EXCEPTION 'ledger entry % is unbalanced by %', NEW.entry_id, diff;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_ledger_entry_balance
    AFTER INSERT ON ledger_posting
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_entry_must_balance();

-- Makes the state "account exists but has no balance row" unreachable, even if
-- somebody inserts an account by hand. That state used to surface as a misleading
-- AccountNotFound on transfer, while the account was plainly there.
CREATE FUNCTION ledger_create_balance_row() RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO ledger_account_balance(account_id, balance)
    VALUES (NEW.id, 0)
    ON CONFLICT (account_id) DO NOTHING;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_create_balance_row
    AFTER INSERT ON ledger_account
    FOR EACH ROW EXECUTE FUNCTION ledger_create_balance_row();
