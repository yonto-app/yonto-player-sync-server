# Sync contract (v1)

How a Yonto Player device keeps its records on a sync server, and how a second device gets the key.
This file is what a server or a client is written against; Yonto Player's design notes say why it is so.
`sync-v1.json` holds the test vectors, written with Node's own crypto rather than by any client or server held to them.

A server holds no key and can read nothing but what this file says it sees.
Devices may also sync with each other over the local network, with no server (*On the local network*).
Anybody may write one: Yonto runs the open-source one (`sync-server/`) with a licence check in front, and a viewer may run the same image.

## Keys

Everything comes from one **sync key**, 32 random bytes made by the first device, and its **generation**, 1 when made and one more at each *Start over*.

| Value | How |
|---|---|
| records key | HKDF-SHA256(sync key, salt `yonto-sync-v1`, info `records`, 32 bytes) |
| ids key | the same with info `ids` |
| auth | the same with info `auth` |
| verifier | SHA-256(auth), hex |

A device sends `auth`; a server keeps only the verifier and compares SHA-256 of what it is sent.
`auth` cannot give back the sync key, so a server that keeps what it is sent still reads nothing.

Each device also has two key pairs on P-256 of its own, made by it and kept in its vault with the sync key: an ECDH one, what the sync key is wrapped to and LAN keys come from, and an ECDSA one, what it signs its own `device` record with (*A device's own record*).
A public key is the 65-byte uncompressed point, base64url.
Neither pair is used for the other's job.

base64url is always without padding.

## A record on the wire

```json
{
  "id": "25162e8f…0787",
  "gen": 1,
  "stamp": { "millis": 1761600000000, "counter": 0, "device": "a1b2c3d4e5f6" },
  "deleted": false,
  "kept": false,
  "sealed": "oKGio6Sl…"
}
```

- `id`: HMAC-SHA256(ids key, kind ‖ `\n` ‖ key), hex.
  A record's kind and key are its identity, the same on every device; the server sees only this.
- `gen`: the generation it was sealed under.
- `stamp`: a hybrid logical clock, written by the device that made the edit and never rewritten by anyone.
  Ordered by `millis`, then `counter`, then `device` as a string.
- `deleted`: a tombstone.
- `kept`: a tombstone kept for good (a source's, a *merged into* record's and a `device` record's); always false on a live record.
- `sealed`: base64url(iv ‖ ciphertext ‖ tag), AES-256-GCM under the records key, a fresh 12-byte iv each time, a 16-byte tag.
  The associated data is `yonto-sync-v1|record|<id>|<gen>|<millis>.<counter>.<device>|<deleted 0 or 1>|<kept 0 or 1>`, so a server cannot move a stamp, flip a flag or give one record another's id.

The plaintext is JSON, written with its fields in this order and `parent` only when there is one:

```json
{"kind":"history","key":"…","v":1,"parent":{"kind":"source","key":"…"},"payload":{…}}
```

A tombstone is sealed too, with an empty payload, so a device that pulls it knows what it deletes.
A device that opens a record checks that its kind and key give its `id`, and drops one that does not open or does not match.
Fields a reader does not know in the payload are kept and passed on.

### Which copy stands

A server keeps one copy per `id`: the one with the greater stamp, and under one stamp the tombstone.
That is all it ever decides, and devices decide the same way, so every replica ends up with the same copy.

A server can still hold a record back or keep serving an older copy; it cannot make one up, change one, or pass one off as another.
A device that has synced with no replica for longer than a tombstone is kept (90 days) takes everything the replica holds, drops what it synced before that the replica no longer has, and offers only what it changed since it last synced, so it never brings back a delete the replica has forgotten.

## The server

Every endpoint is under `/v1` and speaks JSON.
A client sends its credentials only over HTTPS, or plain HTTP to a host on the viewer's own network, and follows no redirect, which would carry them elsewhere.
Unknown fields are ignored on both sides.
A request that succeeds answers 200 with the body shown, or 204 with none where none is shown.

### Revisions

The contract changes under `/v1` too, and each change one side cannot follow takes the next **revision**; this file is revision 2, which put the joining device's signing key in a join's commitment (*Joining*).
A server names its revision on every answer, a refusal included, as `Yonto-Sync-Revision: 2`, and a client names its own on every request the same way.
A server refuses a request naming another revision with 409 `revision` before anything but the per-address rate limit, which it counts first so that refusals cost a flood of them as much as any request; it serves one naming none.
A device works with a server only at its own revision: it reads an answer that names none as revision 1 when it is JSON, empty, or a refusal this file lists (*Errors*, its status and code both), and anything else at that address as no sync server at all.
A server older than the device needs updating, and the device asks it nothing more, so it never starts a join it knows will fail; a server newer than the device needs the app updated.
A device asks for the account (`GET /v1/account`) before it asks to join, so it learns the server's revision before the server holds a request of its own.

On the local network (*On the local network*) anything can advertise the account's tag, answer as a device, and replay a device's sealed record, so a revision is taken only from what a device proved, fresh, for the request it answers: the proven hello (`GET /v1/lan/hello`), whose sealed body carries the answering device's `revision`, left out by a device from before revisions, which is revision 1.
Every request between two devices names its revision as above, and a device refuses one naming another with 409 `revision` before it reads its body, but for the hello and `GET /v1/lan/device`, answered whatever the asker names, so a device a revision apart is still told which revision the other speaks, and by which device.
A device that meets one whose proven hello names another revision exchanges nothing more with it and tells the viewer that device, by name, needs updating, or this one, when it is the newer.
Nothing else says a revision there: a refusal, a header or an unproven answer is no answer, and never tells the viewer a device needs updating, changes what is kept about a device, or stops *Turn on* or a join more than a device not answering would; at most a round that met nobody says it could not reach a device rather than that it found none, which both mean it did not sync.
A device joining on the local network holds no key to prove anything with, so one a revision apart just does not answer it: a device of another revision refuses its request to join before reading it.

### Who is asking

| Header | What |
|---|---|
| `Yonto-Account` | The account: the licence id. |
| `Yonto-Device` | The device's id: the twelve hex digits its stamps carry. |
| `Yonto-Device-Secret` | 32 random bytes of this device's own, base64url, made when it first syncs and kept with the sync key: what tells one device of the account from another. |
| `Authorization` | `Yonto-Sync <auth, base64url>`: proves the device holds the sync key. |
| `Yonto-Licence` | The licence token, sent only to Yonto's server, the one that checks it: it is a credential at the licensing service, so a viewer's own server never gets it. |
| `Yonto-Install` | With the licence token, always: the install id the token was issued to, which proves the token did not leave its device on its own. A server that checks the licence takes the token only when SHA-256 of this, hex, is the token's `dev`, compared in constant time, and keeps it nowhere, not even in a log. |

An endpoint marked *keyed* needs all of them, and refuses a device that is not on the account's list, was removed, or sends another device's secret.
One marked *licence* needs only the account, and the licence token with its install id where the server checks one; a token with no install id, or one that does not hash to its `dev`, is 403 `not_allowed` on every endpoint, so a token that leaked on its own can make, take, reset or join nothing.
A joining device's requests with `Yonto-Join-Secret` carry the account and the licence token too, which a server that checks one reads before the secret.
A server keeps SHA-256 of `auth` (the verifier) and of each device's secret, never either, and compares in constant time.

### Errors

A refusal is an HTTP status with `{"error": "<code>"}`:

| Status | Code | Meaning |
|---|---|---|
| 400 | `bad_request` | The body or a header does not read. |
| 401 | `unauthorized` | `auth` does not match the verifier, or the device's secret is not its own. |
| 401 | `generation` | `auth` matches the previous generation's verifier: the device reads its wrap (`GET /v1/wrap`), which it takes once the viewer accepts it (*Starting over*). A device further behind is answered `unauthorized`, and reads its wrap the same way. |
| 403 | `removed` | The device was removed from the account. |
| 403 | `unknown_device` | The device is not on the account's list (on a keyed request, say once a join that never finished was dropped): it forgets its keys and joins again. |
| 403 | `not_allowed` | The licence does not sync here (on Yonto's: not Pro, revoked or expired). |
| 404 | `no_account` | The account does not sync on this server. |
| 404 | `unknown_device` | `POST /v1/devices/<id>/remove` names a device not on the account's list. |
| 404 | `not_found` | No such join or reset, or no wrap for this device. |
| 409 | `exists` | The account already syncs here, or (`POST /v1/devices`, `POST /v1/joins` and its `allow`) the device is already on its list. |
| 409 | `state` | The join is not in a state that step can follow. |
| 409 | `taken` | A refuse of a join whose new device already read its wrap (*Joining*). |
| 409 | `revision` | The request names another revision than the server's (*Revisions*); the answer names the server's. |
| 409 | `full` | The account holds as many devices as the server allows (20 unless it says otherwise). |
| 413 | `too_large` | The body, or the account, is past the server's limit. |
| 429 | `rate_limited` | Too many requests, in a minute or in flight at once from one address (that one with the connection closed after it), or too many open joins or resets asked, or on a server that checks a licence the licensing service's own limit, passed on; `Retry-After` says when to ask again. |
| 503 | `unavailable` | The server cannot check the licence just now (on Yonto's: the licensing service is down and the licence has no recent answer, or, for a reset, cannot be asked at all), or holds as many request bodies as it has room for; `Retry-After` says when to ask again. |

### Accounts

`GET /v1/account` (*licence*): `{"generation": 3}`, or 404 `no_account`.
What a Pro device asks to learn whether its account already syncs.

`PUT /v1/account` (*licence*): makes the account, with the first device.

```json
{ "verifier": "9cc6…", "generation": 1, "device": { "id": "a1b2c3d4e5f6", "platform": "android-tv", "publicKey": "BL5X…", "signingKey": "BH2c…", "secretHash": "…" } }
```

201 with no body, or 409 `exists`, so no device can drop the synced copy by starting a second key.
`secretHash` is SHA-256 of the device's secret, hex; `signingKey` is its ECDSA public key.

`POST /v1/account/generation` (*keyed*): *Start over*.

```json
{ "generation": 4, "verifier": "…", "wraps": [ { "device": "0f1e2d3c4b5a", "wrap": { "from": "…", "sealed": "…" } } ] }
```

The generation is the current one plus one, or 409 `state`.
The server replaces the verifier, keeps the previous one only to answer `generation` and to hand out wraps, drops every record of an older generation, refuses a push sealed under one from then on, and keeps one wrap per device listed.
Who the device starting over wraps the new key to is *Starting over*.

`DELETE /v1/account` (*keyed*, on every server): drops the account, its records, devices, joins and any reset waiting, so no licence token alone can drop the synced copy.

### Resetting an account

A viewer who lost every device and the recovery key starts again without the key by a *reset*: the account is replaced by a new one, which the device asking makes as `PUT /v1/account` makes one.
It happens only once the licence's owner has confirmed it by email, or seven days after it was asked when no request has proved the account's key since; a licence token alone never resets anything before then.

`POST /v1/account/reset` (*licence*): the body `PUT /v1/account` takes, the new account; 404 `no_account` where the account does not sync here, which the device makes instead.

```json
{ "reset": "4f0c…", "secret": "…", "confirm": "email", "code": "042917" }
```

```json
{ "reset": "4f0c…", "secret": "…", "confirm": "wait", "after": 1762204800000 }
```

- `confirm` `email`, on a server that checks the licence, for one the licensing service holds an email address for: the service has emailed its owner a link, good for an hour, that shows `code`, which the asking device shows too, and the reset happens once the owner confirms there.
  No device's request cancels it, since a device holding the key may be the one the owner is shutting out.
- `confirm` `wait`, for a licence with no email address (one bought in an app that nobody put on an address) and on a server that checks no licence: the reset happens at `after`, seven days on, unless before then a request proves the account's key, which cancels it: any *keyed* request, `POST /v1/devices`, and the wrap endpoints under the previous generation's key.
  So a device that still syncs cancels it at its next sync, and the reset happens only where no device holding the key is left.
- The server asks the licensing service which, never the device, passing on the token and its install id, and answers 503 `unavailable` when it cannot ask, never `wait` in its place.
- A device that asked a `wait` reset and saw it `cancelled` while it holds no key is being kept out by a device that holds it, perhaps one that made the account before the viewer did; it says so and offers to put the licence on an email address (an App Store licence's *Add your email*, which needs the purchase itself), after which a reset asked again is confirmed by email, which no device holding the key can cancel.
- One reset waits per account: asking again replaces the one waiting, whose secret then reads 404, and starts its own email or wait.
- `reset` and `secret` are what the asking device reads it with, as a join's; nobody else gets the secret.
- 429 `rate_limited`, with `Retry-After`, past three asks a day from one install of the licence, or, on a server that checks none, from one address.

`GET /v1/account/reset` with `Yonto-Reset-Secret: <secret>` (and the account, and the licence token where the server checks one, which must be the install's that asked, or it reads 404, as does the `DELETE` below): `{"state": "emailed", "after": null}`, the state one of:

- `emailed`: waiting for the owner's confirmation; on a server that checks a licence, this request is when the server asks the licensing service whether it came, at most every ten seconds, and makes the reset if so, so the asking device asks every few seconds while it shows the code.
- `waiting`: waiting until `after`; a server makes it then, at the asking device's next request or its own hourly sweep.
- `done`: the account is the asking device's, which syncs as the first device of a new account, pushing everything it syncs.
- `cancelled`: a request proved the account's key first.
- `lapsed`: the link was not confirmed within its hour.

404 `not_found` once another reset replaced it, or for a secret that names none.
A reset that ended is kept to be read for seven days, and one left `emailed` for seven days lapses.
`DELETE /v1/account/reset` with the secret: the asking device withdraws it.

A reset made drops every record, device, wrap and join of the account and holds the asking device's verifier, generation and device as `PUT /v1/account` would, in one step, so the account is never left for another to make; every other device is refused from then on (401 `unauthorized`, and its wrap 403 `unknown_device`, so it drops its key) and joins again.

A *keyed* pull (`GET /v1/records`) also answers `"reset": {"asked": 1761600000000, "platform": "ios", "state": "emailed"}` while a reset waits for its email, and `"state": "cancelled"` for seven days after one was cancelled, so every device that syncs says someone asked: the owner is being asked by email, or this device's syncing cancelled it.

### Devices

`GET /v1/devices` (*keyed*): `{"devices": [{"id", "platform", "publicKey", "signingKey", "removed", "added", "installs"}]}`, `added` in epoch milliseconds.
`installs`, on a server that checks a licence and empty on one that checks none, are every licence install (each a token's `dev`) the device proved, not only the last, so a device can't make a removal sign out a throwaway install of its own by syncing under it last: the server takes each from the token and install id of the request that made the account, asked to join, added a device, asked for the reset that made the account, or proved the device's secret since, and never from anything a device says of itself.
A device's name and when it last synced are in its own `device` record, which the server cannot read.

`POST /v1/devices` (`Yonto-Account`, `Yonto-Device`, `auth` and the licence; no device secret yet): `{"id", "platform", "publicKey", "signingKey", "secretHash"}`, a device that got the key from a recovery key rather than a join adds itself; 201, 409 `full` past the device cap, 403 `removed` for an id already removed, 409 `exists` for one already on the list.
Holding the sync key is what any device of the account is trusted for, so it needs no other device's tap; the account's other devices still see that no device let it in (*Starting over*).


`POST /v1/devices/<id>/remove` (*keyed*): the device is refused from then on, under every generation, since its secret no longer counts.
An id that is not on the list is 404 `unknown_device`.
A server that checks a licence forgets what the licensing service last said about every install it lists the device with, so the next request made with its token is checked again there; a body is ignored, so the remover cannot name another.
A device turning Sync off removes itself this way, after pushing the tombstone of its own record, then lets its keys go and takes a new id; it deactivates no install, its own included, and starts nothing over.

A device removing another first deactivates that device's licence installs at Yonto's licensing service, every one of `installs` the server lists it with, so the removed device's tokens stop working there and at every server that checks them.
A device's own record names no install: one it named would be what it chose, the TV's included, and a device that named none would escape.
On the local network alone, or at a server that checks no licence, no install is known and none is deactivated; the question says so by leaving out that the device is signed out of Pro.
The licensing service takes that only with the remover's own install proven (its install id, whose SHA-256 is its token's `dev`), never on a token alone, and only for another install of the same licence (the licensing service's `POST /api/v1/devices/deactivate`).
A device never deactivates its own install, and a removal goes no further when the deactivation is refused or cannot be asked, so the viewer tries it again.
Then it always starts over straight after (*Starting over*), since without that the removed device keeps the key.

`GET /v1/wrap` (`Yonto-Account`, `Yonto-Device`, the device secret and the licence; `auth` not needed): `{"wrap": {"from", "sealed"}}` for this device, or 404 `not_found`.
A listed device that was not removed reads its own wrap with its secret whatever key it holds, so one away for two *Start overs* or more still gets the newest, which only it can open; nothing else is answered to a key that old.
A device the server lists as removed is answered 403 `removed`, and one it does not list 403 `unknown_device`, whatever key it holds and before its secret is checked, so a device any number of *Start overs* behind still learns it was removed or is no longer listed (say after a reset, or a sweep): it then drops its key and keeps what it holds, which is all it would do were any other request answered either.
An `auth` that proves the current or the previous key still counts as proof of the key (*Resetting an account*).
`DELETE /v1/wrap` (*keyed*, the previous generation's `auth` accepted) once taken.

### Records

`POST /v1/records` (*keyed*): a push, of at most the server's batch size.

```json
{ "records": [ { "id": "…", "gen": 1, "stamp": {…}, "deleted": false, "kept": false, "sealed": "…" } ] }
```

```json
{ "taken": ["…"], "refused": [ { "id": "…", "reason": "ahead" } ], "now": 1761600000000 }
```

- `ahead`: stamped more than five minutes past the server's clock; the device stamps it again once its clock is right.
- `generation`: sealed under a generation other than the account's, an older one or one the account has not reached.
- `too_large`: one sealed record past the server's limit.
- `full`: the account holds as many records as the server allows (10,000 unless it says otherwise); a record replacing one already held is never refused for it.

A record taken is merged by *Which copy stands*; one that loses is still taken, since the copy that beat it is what the device will pull.
A record that does not read (an id that is not 64 hex digits, a stamp's device that is not 12, a `sealed` that is not base64url of at least an iv and a tag, a `gen` below 1) or a live record marked `kept` refuses the whole push with 400 `bad_request`, since only a broken client sends one.
`now` is the server's clock, which a device compares with its own to say when its clock looks wrong.

`GET /v1/records?since=<cursor>&limit=<n>` (*keyed*): a pull.

```json
{ "replica": "5c1a…", "records": [ … ], "cursor": 1042, "more": false }
```

- `since`: a cursor this server answered, or 0 to start; a whole number.
- `limit`: 500 when left out, and a server may answer fewer than asked.
- `replica`: this server's database, made when it was; a device that sees a new one starts from 0 and pushes everything it syncs, since what it pushed before is gone.
- `records`: each copy that changed after `since`, in the order the server took them, the newest copy of each.
- `cursor`: what to send as `since` next; `more` says to ask again straight away.

A device passes over a record stamped more than five minutes past its own clock, as a server refuses one pushed (`ahead`), and never moves its clock to it; it keeps its cursor from before that record, so it pulls it again once its clock has caught up.

`POST /v1/forget` (*keyed*): `{"ids": ["…"]}` drops those records with no tombstone, which is how the last device to stop syncing a kind takes it off the server.

A server keeps a tombstone 90 days after it took it, unless it is `kept`.

### A device's own record

Every device writes its public keys into its own `device` record and signs it, so the account's devices learn each other's keys from records only the sync key opens and only that device could have written.
Holding the sync key lets a device seal any record, another device's `device` record included, so the seal alone says nothing about who wrote one; the signature does.
`GET /v1/devices` is the server's own list, what it admits by; a device never takes a key from it, and only checks the keys it holds against it (*Starting over*).

The payload of a `device` record is `{"signed": "<text>", "signature": "<signature>"}`:

- `signed`: the device's own fields as JSON text, kept as it was written, so nothing between the signing and the checking has to write it out the same way again:
  - `name`, `platform`;
  - `publicKey` and `signingKey`, its two public keys;
  - `generation`, the generation of the sync key it held when it signed;
  - `kinds`, the kinds it syncs where they differ from the defaults, and `lastSyncedMillis`;
  - `allowed`, the signing keys of the devices it let in by a join (*Joining*);
  - `removed`, the ids of the devices it removed (*Starting over*).
  
  Fields a reader does not know are passed on inside it, and signed with the rest.
- `signature`: ECDSA P-256 with SHA-256, strict DER, base64url, by the private key of `signingKey`, over `yonto-sync-v1|device|<device id>|<millis>.<counter>.<stamp device>|<signed>`.
  The stamp is in it, so an old copy cannot come back under a new stamp.

A device signs its record again whenever a field changes, its generation included, and never writes another device's.
A device takes another's record, pulled from a replica, pushed to it on the local network, or handed over there, only when:

1. it is live, or it is a tombstone its own device signed: a device that joins again, starts fresh or turns Sync off deletes its old record with a tombstone whose sealed payload is `{"signature": "<b64url DER>"}`, signed over `yonto-sync-v1|device|<device id>|<millis>.<counter>.<stamp device>|deleted` by the key pinned for that id, its stamp's device the record's own id, and any other tombstone of a `device` record is refused;
2. its stamp's device is the record's own id, and its signature holds under the `signingKey` it names;
3. its `generation` is no greater than the generation it is sealed under, so a device cannot claim a key it never held;
4. its keys are the ones pinned for that id: the first signed record a device takes for an id pins its `publicKey` and `signingKey` for as long as the device stays in that account (trust on first use), and a later one naming any other key is refused, as is one naming a key pinned for another id;
5. this device holds no tombstone of it and no record of it nobody signed (*A record nobody signed*).

A device that has its record pinned from a join holds it from the join itself, not from a record: the allowing device saw the new device's signing key in the request, its code covering it, and lists it in its own `allowed`.
A device keeps, on itself only, when it first took each device's record, and from its join its **chain**: the device that let it in, then that device's own chain as the wrap gave it (*The wrap*), each as `{"publicKey", "signingKey"}`; a device that made the account or recovered into it has none.
The first is taken from the allowing device's own record among the wrap's `devices`, the one signed record there carrying the public key the wrap was opened from, and none is kept when there is not exactly one; an entry without both keys counts as none.
Pins belong to the account they were taken in: a device that holds no key and then makes an account, joins one, recovers into one or has its reset made first forgets every `device` record it held, pins and removals with them, keeping only its own record under the id it syncs with and its own tombstone not yet pushed, so an account it left or was removed from lends the new one none of its devices.

A device's **code** is six characters of Crockford's base32 (as the recovery key's) of the top 30 bits of the first four bytes of SHA-256(`yonto-sync-v1|code|<its signing key>`), shown as two groups of three.
A name is whatever a device gives itself, so two can share one, or a removed device can take the remover's; wherever the apps name a device to the viewer, they give its code beside the name, and each device shows its own code on its Sync page.

A device is **trusted** when the record held of it is signed and holds, and no standing removal names it.
A removal of a device by another stands when the removing device is trusted itself and its record states a higher `generation` than the removed one's: a device can only remove one it has moved past, which a removed device, never wrapped the new key, cannot do back, and a removed device's removals go with it.
Removers are settled from the newest generation down, since a device can only be removed by one newer than itself; a device also takes the devices it removed itself as removed, whatever any record says, and their removals with them.
Only a trusted device's keys are used: to wrap to at *Starting over*, to take a wrap from, and on the local network to know a device by.

**A record nobody signed.**
A `device` record that is not signed is never taken from another replica, nor does anything take the place of one held, since nobody can tell who signed first for it.
A device whose vault holds no signing key (an install from before records were signed, which only ever existed on test devices) lets its key go, takes a new id and joins again as a new device, as one the server no longer lists does.

### Joining

A device that does not hold the key asks a device that does.
The server carries the request; the two devices check each other by the code both screens show (*The match code*), which a fresh nonce from each side folds in, the new device's committed to before it sees the other's, so a server can neither join a device of its own nor hand the new device a key of its own.

A join goes `waiting` → `opened` → `revealed` → `allowed`, and from any of them to `refused`, or to `lapsed` ten minutes after it was made.
A step out of that order is 409 `state`; `open` is taken once.
The server cannot be trusted to keep that order, so each device keeps it for itself: the allowing device sends its nonce before it reads a revealed key, and the new device reveals only after it has read the opening key and nonce.

`POST /v1/joins` (*licence*): the new device asks.
A device whose id the account already has, listed or removed, as one left behind by *Start over* or stopped before it took its wrap still holds, takes a new id and asks again.

```json
{ "device": "0f1e2d3c4b5a", "platform": "ios", "commitment": "…", "secretHash": "…" }
```

```json
{ "join": "7d0c…", "secret": "…" }
```

An account with more than ten requests still waiting or opened answers 429 `rate_limited`, with `Retry-After`, until one of them ends, and so does one with two of them from the same install of the licence (on a server that checks none, from the same address), so one token cannot fill the account's room for requests.
A device id already on the account's list is 409 `exists`, and 403 `removed` for one removed: a join only ever adds a device, never replaces the key and secret of one the account has.
A device answered `exists` takes a new device id and asks again under it.

`commitment` is SHA-256(`yonto-sync-v1|commit|<its public key>|<its signing key>|<its nonce>`), hex, the nonce 16 fresh random bytes, base64url, made for this request alone; `secretHash` is its device secret's, as for the first device.
The signing key is in the commitment, so the code that checks the public key checks it too, and the allowing device pins it from the join (*A device's own record*).
`secret` is what the new device reads its request with; nobody else gets it.

`GET /v1/joins` (*keyed*): `{"joins": [{"join", "device", "platform", "commitment", "publicKey", "signingKey", "nonce", "state", "created", "openedBy"}]}`, the requests not yet allowed, refused or lapsed, `publicKey`, `signingKey` and `nonce` once revealed, and `openedBy`, the id of the device that opened it, once opened.
A device already syncing asks every few seconds while the app is open; there are no push notifications.
Every such device lists a request, and it opens only on the one the viewer tells to answer it; the others show it answered on the device `openedBy` names.
A device that opened a request and can no longer finish it, having lost its nonce when the app stopped, refuses it, so the new device is told and can ask again.
A new device that stopped while its request waited, its keys gone with it, refuses that request with its join secret the next time it runs, so no device opens it to wait for a reveal that never comes.
It never refuses one that reads `allowed`, or once it holds a key, and forgets the request before it keeps the key, since refusing an allowed request takes it off the list.

`POST /v1/joins/<join>/open` (*keyed*): `{"publicKey": "…", "nonce": "…"}`, the opening device's own key and 16 fresh random bytes made for this request.
The allowing device keeps the commitment it saw before it opened, and goes on only with a revealed key and nonce that match it.

`GET /v1/joins/<join>` with `Yonto-Join-Secret: <secret>`: `{"state", "publicKey", "nonce", "wrap"}`, `publicKey` and `nonce` (the opening device's) once opened and `wrap` once allowed.
Answering the wrap marks it read, in the same transaction a refuse checks it in, so a wrap and a refuse never cross.
The new device keeps the first opening key and nonce it reads, and then reveals its own; a state already `revealed` or `allowed` is its own reveal, which landed though the answer was lost, so it is not sent again.
A reveal the server refuses ends the join on the new device, which refuses its request so the opening device stops waiting.

`POST /v1/joins/<join>/reveal` with `Yonto-Join-Secret`: `{"publicKey": "…", "signingKey": "…", "nonce": "…"}`, the new device's, which must match its commitment (400 `bad_request` otherwise).
Both devices now show the code.

`POST /v1/joins/<join>/allow` (*keyed*): `{"wrap": {"from", "sealed"}}`, after the viewer tapped *Allow*; 409 `full` when the account already has as many devices as the server allows, as `POST /v1/joins` answers too, so the new device can say so before anyone taps, and 409 `exists` when its id reached the list since it asked.
The server adds the new device to the list, with its secret and the signing key it revealed.
The allowing device adds that signing key to its own record's `allowed` only once the server took the allow, or, the answer lost, lists the new device with that signing key, and then only once the new device proved it got in: a trusted `device` record signed with that key, which only a device holding the sync key can make, held by the allowing device within ten minutes of the allow, the request's own life, judged by when the record reached it and never by the record's stamp, which the new device picks, however much later it checks.
One not proven by then never counts, whenever it turns up, so a new device that cancelled or closed after the allow, or one let in again some other way, is not one this device let in; the keys waiting are kept on the device alone, across a restart.
On the allowing device an allow and a refuse of one request take turns, and *Don't allow* wins whenever it still can: a refuse waits for an allow on its way and is sent whether or not that allow went through, which takes the wrap and the device back unless the new device already read the wrap; then the server answers 409 `taken`, the key stays, and the viewer is told that only removing that device with *Start over* takes it back.
Otherwise, and when the refuse can't be sent, the key is not trusted; an allow after a refuse is not sent.
A device added so that makes no *keyed* request within a day of being allowed is taken off the list again, with any wrap kept for it, so a join that never finished holds no place under the device cap; one that comes back later is answered 403 `unknown_device`, drops its keys and joins again.
Only the device that opened the request may allow it, and the wrap's `from` must be the key it opened with, since that is the key the new device read its code from; otherwise 409 `state`.

`POST /v1/joins/<join>/refuse` (*keyed*, or with `Yonto-Join-Secret`): either device says no; the server takes the new device off the list if it was added, and drops its wrap.
Keyed, once the new device read its wrap it answers 409 `taken` and changes nothing: the key is out, and only a removal with *Start over* takes it back.
With `Yonto-Join-Secret` it does so only until that device has made a keyed request; after that it answers 409 `state`, since the secret outlives the join by a day, and only a removal takes the device off.

### The wrap

The sync key reaches a device sealed to its public key:

- shared secret: ECDH(the sender's private key, the receiver's public key), the 32-byte x coordinate;
- key: HKDF-SHA256(shared secret, salt `yonto-sync-v1`, info `wrap|<sender public key>|<receiver public key>`, 32 bytes);
- `sealed`: base64url(iv ‖ ciphertext ‖ tag), AES-256-GCM, associated data `yonto-sync-v1|wrap|<sender public key>|<receiver public key>`;
- plaintext: `{"key": "<sync key>", "generation": 1, "server": {"kind": "yonto"}, "removed": ["…"], "devices": [{"id", "stamp": {"millis", "counter", "device"}, "payload"}]}`, where `server` is `{"kind": "yonto"}`, `{"kind": "own", "address": "https://…"}` or `{"kind": "lan"}`, `removed`, left out when empty, the ids of the devices the *Start over* that made it removed, and `devices`, left out when empty and given only in a join, the live, signed `device` records of every device the allowing device trusts, its own included, as it holds them, and `chain`, left out when empty and given only in a join, the allowing device's chain (*A device's own record*).
- The joining device takes each of `devices` as it takes any `device` record (*A device's own record*), checked and pinned, before it pulls anything from a replica.
  A replica keeps one copy of a record, so a device holding the key could push a tombstone of the TV's record stamped as the TV's within the skew allowed, which a device that pinned the TV refuses but a replica keeps; without this, a device that joined after it would never hear of the TV, and leave it out of its next *Start over*.
  The record comes from the device the viewer let it in on, so a replica can lose or replace it but never keep it from a new device.
  A server keeps a join's wrap of up to 256 KiB of base64url, room for the records of a full account, and a *Start over*'s, which carries none, of up to 4 KiB.

A device opens a wrap only from a public key it checked: in a join, the one its code was read from; at *Start over*, the `publicKey` of a trusted device (*A device's own record*).
A *Start over* wrap whose generation is not above the device's own is refused, and one that is, from a server or on the local network, is taken only once the viewer accepts it on that device (*Starting over*).

### Starting over

*Start over* is the next generation, wrapped to every device that should keep syncing.
A device removing another always starts over straight after, since otherwise the removed device keeps the key: it removes the device at the server first (`POST /v1/devices/<id>/remove`), then starts over, and once the server took it, writes the removed id in its own record's `removed`, stating the new generation.

The device starting over wraps to the account's trusted devices, but itself and the one it removes, and to each only by the keys pinned for it.
On an account that syncs at a server it first reads `GET /v1/devices`, and a device the server does not list, lists as removed, or lists with another `publicKey` or `signingKey` gets no wrap; it joins again.
On the local network alone the pins are all there is.

When it removes a device, it wraps only to the devices it traces to one the removed device could not have made up: itself, the devices of its chain, each only by a trusted record with both of the entry's keys, since a record's `publicKey` is whatever the device signing it says, and then, over and over, any device whose signing key a device already traced lists in `allowed`; the removed device's own record, and any record under its keys, vouches for nobody.
Holding the key, the removed device can sign any number of records under ids and keys of its own, letting itself or each other in, or recover new devices with it, so nothing a record says about itself, nor when it reached a device or a server, counts: only who let whom in, signed by a device already traced.
A chain is the word of each of its devices from when the viewer used it to let the next one in, so it still counts once one of them is the device removed.
A server's list is a condition beside this, never in its place.
A device left out so that is the viewer's own, one recovered with the recovery key among them, joins again.

Before the viewer confirms, the device shows every device it is about to wrap to, by name and code, and every one it leaves out and why, a device another removed included, so a device the viewer expected is never left out without a word.
It wraps only to devices the viewer saw in that list.

A device never takes a *Start over* wrap it did not make by itself, whether a server holds it or a device on the local network hands it over.
A removed device still holding the old key can start over too, racing the device removing it on the local network, or helped by a server that hides the remover's *Start over* and hands out its own, and a device that was away until then learns of the removal only from the remover's record under the new key, which it can't open first.
So the device asks the viewer on it, naming by name and code who started over and whom its wrap says it removed (`removed`, *The wrap*), and takes only a wrap the viewer accepts, checking the device that made it is still trusted as it does; two at the same generation from different devices are both shown as such, for the viewer to pick the one they made.
A device that declines, by the answer that says so (*Don't use it*), keeps its key, is never asked about that wrap again, across a restart too, and can join again.
Putting the question away (*Not now*, Back or a tap outside it) decides nothing: it is not asked again at the syncs the device makes by itself, but nothing syncs while it waits, so Settings → Sync says so, naming the device by name and code, with a button that asks again, and opening that page or *Sync now* asks again too, as does the app starting again; that it was put away is kept in memory alone, so a restart always asks.

### The match code

Both devices show six digits: SHA-256(`yonto-sync-v1|match|<joining public key>|<allowing public key>|<joining nonce>|<allowing nonce>`), its first four bytes as a big-endian unsigned number, modulo 1,000,000, padded with zeros.
A server in the middle knows both devices' long-term keys, so the nonces are what it cannot choose around: whatever it shows the allowing device, it committed to before that device sent its nonce, and whatever it shows the new device, it sent before the new device revealed its nonce.
So each screen's code is fixed before the server learns what would make it match the other, and it has one chance in a million, once per request.

### The recovery key

The sync key and its generation as text a viewer can keep: two bytes of generation (big-endian), the 32-byte key and the first two bytes of SHA-256 of those 34, in Crockford's base32 (`0123456789ABCDEFGHJKMNPQRSTVWXYZ`), in groups of four joined by `-`.
Reading one back ignores case, spaces and hyphens, reads `I` and `L` as `1` and `O` as `0`, and refuses one whose check does not match.

## On the local network

Devices of one account on the same network sync with each other directly, with no server, alone or beside one.
Each is a replica like a server, and records go across sealed exactly as for a server.
Everything else two syncing devices send each other is sealed as well and proven by the pair, so plain HTTP carries it, and nothing on the air names a device.

### Keys between two devices

Two devices of the account derive, from ECDH(own private key, the other's public key) and the two public keys base64url in sorted order (`<first>|<second>`), with HKDF-SHA256 and salt `yonto-sync-v1`, 32 bytes each:

- proof key: info `lan|<first>|<second>`;
- seal key: info `lan-seal|<first>|<second>`.

Each device takes the other's public key only from the record of a trusted device (*A device's own record*), so a device removed, or one whose record is not signed, has no key at any other device.
These keys need no sync key, so two devices a generation apart still know each other.

Every holder of the sync key also derives from it, as the records key is derived:

- account seal key: info `lan-device`, what a device's own `device` record and its introduction are sealed under;
- recovery key: info `lan-recover`, what a device recovering with a recovery key proves it holds the key with.

### Finding each other

A device advertises the DNS-SD service `_yonto-sync._tcp` on the port it listens on, under an instance name made at random each time, with one TXT key, `a`: the account tag of the day, the first 16 hex digits of SHA-256(`yonto-sync-v1|lan|<licence id>|<UTC date, YYYY-MM-DD>`).
The tag needs no key, so a device of the licence that holds none yet finds the account's devices by it, and it changes every day; a looking device takes today's and yesterday's.
A device advertises, listens and looks only while the app is open and it syncs on the local network alone, or while Settings → Sync is open on a device that syncs anywhere; a device that holds no key looks only while a join or a recovery is open.
So a device that does not use the local network puts nothing on it.
A device that syncs on the local network meets every other device it finds with the tag about once a minute, and at once when one appears.

### Names and proofs

A device's name with another on a UTC date is the first 16 hex digits of HMAC-SHA256(their proof key, `name|<its own public key, base64url>|<date>`): only the two of them can tell it is that device, and it changes every day.

Every request between two syncing devices, and every answer to one, carries `Yonto-Proof: <name>.<millis>.<nonce>.<mac>`:

- `name`: the sender's name with the other device, on the UTC date of `millis`;
- `millis`: the sender's clock, which decides only whether the proof is fresh, since a clock can be set back and repeat a millisecond;
- `nonce`: 16 random bytes, base64url, made for this proof alone, so no two proofs are alike whatever the clock says; a proof without one is no proof;
- a request's `mac`: base64url(HMAC-SHA256(proof key, `<method>|<path>|<millis>|<nonce>|<body hash>`)), where `path` is the request target with its query and `body hash` SHA-256 of the body as sent, hex, the empty body's for none;
- an answer's `mac`: the same, over a nonce of its own, with `answer` for the method and the request's `mac` for the path, so an answer is good for that one request alone, its nonce included.

A request also carries `Yonto-Head: <base64url(HMAC-SHA256(proof key, head|<Yonto-Proof>|<method>|<path>|<Content-Length>))>`, which a device can check before it reads any of the body, as `mac` cannot be.

A body, either way, is raw bytes iv ‖ ciphertext ‖ tag, AES-256-GCM under the seal key with a fresh 12-byte iv, its associated data `<method>|<path>` for a request and `answer|<request mac>` for an answer.

The receiving device knows the sender as the trusted device whose name with it that day is `name`, and takes the request only if `mac` holds, `millis` is within five minutes of its own clock, and it has not taken a proof with that `nonce` before; it remembers the nonce of every proof it took for ten minutes, as long as one could still be fresh, and keeps them across a restart of the app, so a proof seen on the network just before one is not taken after it.
A device takes a successful answer only with a proof from the device it asked, under that device's name, and opens its body with the seal key.
A refusal comes as a server's does (*Errors*), with no proof, since the refusing device may not know the asker, but for the one below.
A device says one to the viewer at most and never acts on it: a stranger who sends one can stop a meeting, which anything on the network can do anyway, and nothing more, never a device removed, a key dropped or a join started.

One refusal comes proven: a request whose proof holds under the name and key of a device this one holds removed (a standing removal names it, and its signed record is held), fresh and not taken before, is answered 403 `{"error": "removed"}`, sealed and proven as any answer (`answer|<request mac>`), with this device's name with that one.
The two derive their pair from the key pairs alone, so it holds across generations, and only a device of the account that held the removed one's record can make it.
The removed device takes it as removed only from a device it trusted, under that device's name and proof, and then only says so (*Meeting*); an unproven 403, or one proven under any other key, is any other refusal.

### Endpoints

Every endpoint is under `/v1/lan`; one marked *proven* is sealed and proven as above.
A request's head is read under a small cap, and its body only once the head is not refused.
A proven push of records may send up to 16 MiB, and is refused before its body is read unless its proof names a device of the account, its `Yonto-Head` holds for that proof and the declared length, and the proof is fresh and not taken before; any other request may send at most 16 KiB.

`GET /v1/lan/hello`, asked without a proof: `{}`, which says only that a device of the account is there.
It is a hint and no more, since anything on the network can say it: it may suggest *Join*, and never refuses *Turn on*.

`GET /v1/lan/hello` (*proven*): `{"now": 1761600000000, "generation": 3, "revision": 2}`, this device's clock, key generation and revision (*Revisions*).
A hello whose `millis` is more than five minutes from this device's clock is not taken: it is answered 409 with the same body, sealed and proven for that one request, and changes nothing on the device that answers, so a hello sent again later teaches nothing new and plants nothing.

`GET /v1/lan/device`: this device's own `device` record, `{"record": {…}}`, sealed under the account seal key with associated data `/v1/lan/device`, so only a holder of the sync key reads it.
A device takes another's key from this when it holds no trusted record of that device, once it opens, is not the device itself, and is taken as *A device's own record* says.

`POST /v1/lan/devices` (*proven* under the key the record names): `{"record": {…}}`, the asker's own sealed `device` record, sealed under the account seal key with associated data `POST|/v1/lan/devices`, which a device sends the first time it meets another while the app is open.
It is refused when the record does not open under the sync key, when it names a device removed here (403 `removed`), and when it is not taken as *A device's own record* says, as one naming keys other than those pinned for it or pinned for another device (409 `state`).

`GET /v1/lan/records` (*proven*): `{"replica": "<device id>", "records": [ … ], "cursor": 42, "more": false}`, everything this device syncs, tombstones included, at once.

`POST /v1/lan/records` (*proven*): a push, answered as a server's (`taken`, `refused`, `now`); `ahead` and `generation` mean what they do there, and a record that does not open is neither taken nor refused.

`GET /v1/lan/wrap` (*proven*): `{"wrap": {…}}`, the wrap this device made for the asker at a *Start over*, or 404 `not_found`.

`POST /v1/lan/wrap` (*proven*): `{"wrap": {…}}`, the asker handing this device the wrap it made for it; 409 `state` unless it is from a trusted device's `publicKey` and of a newer generation.

`GET /v1/lan/recover`, its `Yonto-Proof` made as above under the recovery key with 16 random hex digits for the name: `{"generation": 3}`, proven under the same key, when this device holds the same sync key and the proof is fresh and not seen before; 401 otherwise.

### Meeting

A device that finds a service knows which of the account's devices it is from that device's sealed record (`GET /v1/lan/device`), or, for one a generation apart whose record it cannot open, from whichever device it trusts, and met at no other service that round, answers a proven hello there; anything else it leaves alone.
A sealed record can be replayed by anything on the network, so a device counts as met only once it has answered under its proof that round, never because its record opened.
Anything on the network can also answer a record that does not open, so it is sent no hello as a device met, and a round sends at most 16 such hellos, all at once, each round going on from the pair (advertisement and device, in the order of their address, port and device id) after the last one sent, so every pair is tried within ceil(pairs / 16) rounds and the hellos hold a round up for one call's wait at most; the records asked for before them still cost one wait for every 16 advertisements that do not answer.
It asks every service it does not know yet at once, and a service that gives no answer at all within a few seconds is left out of that round, never asked again as each device, so an advertisement nothing can reach (a stale record, a device on an isolated Wi-Fi) holds up no device that answers; only a push or a pull of records is given longer.
Anything on the network can copy the tag and answer every one of those calls, so an answer over 64 KiB is read no further and taken as no answer, but a push's or a pull's, which may be up to 16 MiB; and the services found are asked at most 16 at a time, however many advertise the tag.
The first time in a run that it meets a device it introduces itself (`POST /v1/lan/devices`), then says hello with a proof.
If the two clocks differ by more than five minutes nothing is exchanged and the asking device tells the viewer, with both clocks; the other device learns it the same way when it meets this one.
If their generations differ, the one behind takes its wrap from the other (`GET` or `POST /v1/lan/wrap`) and they meet again; with no wrap there, they exchange nothing.
A device that, at one round of meetings, syncs with none and finds one proving a newer generation with no wrap for it has been left behind by a *Start over*, and tells the viewer to join again (two taps) rather than go on trying; it says so until a round syncs again. A device that syncs at a server and meets the LAN only while its Sync page is open takes the new key from the server instead, and is told to join again only once the server too says it is behind and holds no wrap for it.
One that, at a round, syncs with none and is told under a peer's proof that it is removed (*Names and proofs*) says another device removed it and offers to join again as a new device; it never drops its key or its records by itself, and a device left behind keeps its own line.
While it holds no record that opens, a device finds who a service is by a proven hello as each device it trusts and met nowhere else, so a proven refusal there is that device too.
Then the device that asked pulls everything the other syncs and pushes everything it syncs itself, so every meeting is the whole exchange, but for a device that synced with no replica for longer than a tombstone is kept, which offers only what it changed since and drops only records of kinds the other device syncs, by its `device` record.
A peer refuses a record stamped more than five minutes ahead of its own clock, as a server does, and the device stamps those edits again after pulling from that peer.
A device pulling from a peer passes over such a record too, as it does from a server.
On an account that syncs at a server too, what a peer takes is still pushed to the server, and while a push to the server is unanswered a refusal on the local network leaves stamping again to the next sync with the server, since only the server can hold that edit.
`POST /v1/forget` has no counterpart: forgetting is the server's.

### Joining on the local network

The same steps and states as *Joining*, served by a device that syncs, with no licence check and nothing between the two devices.
The new device asks each device it finds with the tag, under an id made for that request alone rather than its own, polls its request with its secret, goes on with the first device that opens it, and refuses it to the others.
A device that syncs and is listening shows those requests beside a server's.

`POST /v1/lan/joins`: `{"device": "<an id for this request>", "platform": "ios", "commitment": "…"}`, answered `{"join": "…", "secret": "…"}`; at most a few requests wait at once and two from one address, and one more is 409 `state`; a `platform` that is blank or longer than 32 characters is 400 `bad_request`, as on a server.
`GET /v1/lan/joins/<join>`, `POST /v1/lan/joins/<join>/reveal` and `POST /v1/lan/joins/<join>/refuse` with `Yonto-Join-Secret` are the server's `GET /v1/joins/<join>`, `reveal` and `refuse`; a wrong secret is 404 `not_found`.
Opening, allowing and refusing happen on the device itself, which keeps the same order a server would: a refuse wins only until the new device has fetched the wrap, and after that is `taken`, as on a server, and the wrap is the same (*The wrap*), its `server` `{"kind": "lan"}` when the account syncs only on the local network.
A device joined on the local network to an account that syncs at a server adds itself there with its own secret, as a recovered device does; once it has opened the wrap it does not ask the device that allowed it again, which may have left the network, and tries the add until it can.
One cancelled after it tried that add, which may have landed with its answer lost, removes its entry there under that secret (`POST /v1/devices/<id>/remove`, best effort, an id never listed being 403 `unknown_device`) and takes a new id, so the entry holds no place under the device cap and a later join never meets it.
Once it holds the key it introduces itself to the device that allowed it, and they sync.

### No server at all

On an account that syncs only on the local network the first device makes the key and tells nobody, whatever answers on the network; *Start fresh* is the same with the old key dropped.
A recovery key is checked only against the devices there, every one found asked, and only those that prove they hold that very key (`GET /v1/lan/recover`) count; no device keeps a key it moved on from.
So words older than what the devices there hold read as wrong, not out of date, and words a device there still holds, one that has not taken a *Start over*'s wrap yet, recover onto that device's generation.
A device recovered so is left behind once that device catches up, and is told to join again (*Meeting*).
*Start over* keeps each wrap on the device that made it until that device has taken it, and hands it over when they next meet.
A device takes a wrap handed over so only once the viewer accepts it, as *Starting over* says.

### What the network still sees

Something on the same network as a device that syncs there, holding no key, can still learn:

- that a device of some account syncs there, and that account's tag of the day, which ties one licence's devices together that day but not from one day to the next, unless it knows the licence id;
- each device's address, port and when it is on the network, which the network sees of every device anyway;
- each sending device's clock, in the clear in every proof, and so how far it is from the right time, which stays much the same from day to day and network to network and so ties one device's days together, weakly;
- when two devices meet and how much they send each other, and so roughly how many records an account holds;
- during a join, the new device's platform, its public keys and nonce and the allowing device's public key and nonce, which tell nothing without the two screens.

It cannot learn a device's id or name, which of the account's devices a request comes from beyond its address, or anything in a record.
