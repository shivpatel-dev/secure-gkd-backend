ALTER TABLE authentication_identities
    ADD COLUMN role VARCHAR(5);

UPDATE authentication_identities
SET role = 'USER';

ALTER TABLE authentication_identities
    ALTER COLUMN role SET NOT NULL,
    ADD CONSTRAINT authentication_identities_role_check
        CHECK (role IN ('USER', 'ADMIN'));
