package com.meetingai.entity;

/**
 * Application roles. Kept minimal for now — every self-registered user is
 * USER. ADMIN exists so we have somewhere to grow into (e.g. an endpoint
 * that lists all meetings across all users) without another migration.
 */
public enum Role {
    USER,
    ADMIN
}
