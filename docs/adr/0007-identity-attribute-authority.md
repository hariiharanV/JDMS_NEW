# 0007 — Which system owns which identity attribute

- Status: Accepted
- Date: 2026-10-24

## Context

B-01 says the token carries `tenant`, roles, `branch_scope` and `clearance`, and also that an admin can set branch
scope and clearance in JDMS Next. If both the token and JDMS Next could set the same attribute, they would disagree,
and a change made in one would be silently overwritten by the other.

## Decision

| Attribute | Owner | How JDMS Next uses it |
| --- | --- | --- |
| Subject, username, display name, email | Keycloak | Mirrored into `user_profile` on every request when changed (audited) |
| Tenant | Keycloak (admin-only user attribute) | Read from the token on every request; must name an active tenant, else 401 |
| Roles | Keycloak realm roles | Read from the token on every request; mirrored into `role`/`user_role` for reporting |
| Branch scope, home branch, clearance | JDMS Next `user_profile` | Token values only seed a new profile; afterwards the profile wins and admins change it through the JDMS Next API |

In the realm, `tenant`, `branch_scope`, `home_branch` and `clearance` are declared user-profile attributes that only
administrators can view or edit; users cannot change them through the account console (verified).

## Consequences

- A scope or clearance change takes effect on the user's next request, not at token expiry.
- A role removed in Keycloak takes effect when the user's current access token expires (5 minutes in the local realm).
- Integration clients (service accounts) are profiled the same way, with `principal_type = CLIENT`.
- If Keycloak presents a known username with a new subject (the user was deleted and recreated), login is refused
  (401, WARN log) instead of handing the old profile's access to the new account; an administrator renames or
  disables the old profile. The local realm gives dev users fixed IDs so recreating the container does not do this.
