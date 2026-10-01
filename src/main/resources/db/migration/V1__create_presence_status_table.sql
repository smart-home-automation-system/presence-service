-- One row per period of an unchanged status: a row is inserted only when the status of a member
-- changes, every pass that confirms it just moves last_checked_at of the member's latest row.
-- member_name is the name of the household member in database-service - another database, so there
-- is no foreign key and no cascade; rows of a removed member stay until the retention job (HAS-154).
CREATE TABLE presence_status
(
    id              INT PRIMARY KEY NOT NULL UNIQUE GENERATED ALWAYS AS IDENTITY,
    member_name     VARCHAR(50)     NOT NULL,
    status          VARCHAR(10)     NOT NULL,
    started_at      TIMESTAMP       NOT NULL,
    last_checked_at TIMESTAMP       NOT NULL
);

ALTER TABLE presence_status
    ADD CONSTRAINT presence_status_status_check CHECK (status IN ('PRESENT', 'ABSENT'));

-- the latest row of a member is its highest id (read and updated by every pass)...
CREATE INDEX presence_status_member_latest_idx ON presence_status (member_name, id DESC);

-- ...while reports read a member's periods by time
CREATE INDEX presence_status_member_started_idx ON presence_status (member_name, started_at DESC);
