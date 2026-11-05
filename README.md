# Yonto Player sync server

The server Yonto Player's Sync talks to.
Run it yourself and your devices keep their history, watch list, positions, recent searches and sources in step through a box you own instead of Yonto's.
It is the same code Yonto runs for its own instance, which only adds a licence check in front.

It implements the sync contract in [`contracts/sync-v1.md`](contracts/sync-v1.md), with its test vectors beside it in `contracts/sync-v1.json`.
Anybody can write another server against that file.

## What it can see

Nothing you watch.
Every record is encrypted on your devices with a key that never leaves them, and the server only stores the ciphertext.
It keeps:

- the account id (your licence id) and each device's id, platform and public key;
- for each record, an opaque id (an HMAC), its timestamp, whether it is a deletion, and the encrypted blob;
- SHA-256 hashes of the value a device proves it holds the key with and of each device's own secret, never the values themselves;
- while a device is joining or after *Start over*, the key wrapped to that device's public key, which only that device can open;
- while a device that lost the key asks to *Start fresh*, the account it would make instead (a new verifier, that device's id, platform and public key) and when it asked, until it is made or cancelled, and a week after.

It never gets your licence token or your install id: the apps send those only to Yonto's own server.

So a copy of its database tells someone when your devices synced and roughly how much you have, but no title, source, address or password.
If you lose every device and never saved the recovery key, nobody can decrypt what is here, you included.

## Run it

With Docker:

```sh
docker build -t yonto-sync-server .
docker run -d --name yonto-sync -p 8080:8080 -v yonto-sync:/data --restart unless-stopped yonto-sync-server
```

Or with Compose:

```yaml
services:
  sync:
    build: .
    restart: unless-stopped
    ports:
      - "8080:8080"
    volumes:
      - sync-data:/data
    environment:
      SYNC_ACCOUNTS: "your-licence-id"
volumes:
  sync-data:
```

Without Docker it runs on JDK 25. `./gradlew installDist`, which downloads that JDK for the build if this machine has none, (in Yonto Player's own repo, `./gradlew -p sync-server installDist`) builds `build/install/yonto-sync-server/bin/yonto-sync-server`, which runs on the JDK in `JAVA_HOME`.

Then in Yonto Player, Settings → Sync, pick your own server and give it the address.

## Put it behind TLS

The server speaks plain HTTP.
Anywhere beyond your own Wi-Fi, put it behind a reverse proxy that does HTTPS, for example Caddy:

```
sync.example.net {
    request_body {
        max_size 9MB
    }
    reverse_proxy localhost:8080
}
```

and set `SYNC_BEHIND_PROXY=true`, so the rate limit counts each client's address rather than the proxy's.
Only set it when a proxy really is in front: the server then trusts the last `X-Forwarded-For` entry.

Each request has a virtual thread of its own, so a client sending its headers or its body slowly holds up nobody else, with or without a proxy.
The server drops a connection whose request takes more than 30 seconds to arrive, or whose answer more than 30 to go out, which is as long as the apps wait anyway, and holds at most 2,000 connections at once, which fit in its memory (`-Dsun.net.httpserver.maxReqTime=<seconds>`, `maxRspTime` and `-Djdk.httpserver.maxConnections=<n>` in `JAVA_OPTS` change them).

One client, an IPv4 address or an IPv6 /64 (behind a proxy, the address it puts last in `X-Forwarded-For`), may have 64 requests in flight, which a household syncing every device at once behind one address never comes near.
One more is answered 429 and its connection closed at once, without reading what it still sends, so a client cannot hold the server's connections however many it opens.
Request bodies held at once are capped at a sixth of the heap, and never less than four bodies of `SYNC_MAX_BODY_BYTES` (32 MiB with the defaults on a 192 MB heap); one client may hold a quarter of that, and never less than one whole body, and each body's first 16 KiB count against neither, so a small request always gets through.
Past either, a request is 503 `unavailable` for a few seconds.

What is left is slow headers on a server with no proxy in front: the 64 are counted once a request's headers are in, so until then one client can hold connections up to the 2,000.
A proxy reads the headers itself before it passes a request on, which is one more reason to put one in front.
The `request_body` lines above keep a body larger than `SYNC_MAX_BODY_BYTES` from reaching the server; one still arriving after 30 seconds the server cuts itself, proxied or not.

## Who may make an account

Left alone, anybody who can reach the server can make an account on it.
That is fine on your own network, but on the internet a stranger could use your box to store their own ciphertext, up to the size limits below.
They can never read or touch your account, since every request is checked against the key your devices hold.
To close it, set `SYNC_ACCOUNTS` to your licence id, or several separated by commas.
So you can turn Sync on once with it unset, list the accounts on the server (`docker exec yonto-sync /opt/yonto-sync-server/bin/yonto-sync-server accounts`, below), and then set it.

## Accounts

The same binary, given a command, works on the database in `SYNC_DB` instead of serving, and the server never logs an account's id:

- `yonto-sync-server accounts` prints every account's id (the licence id), oldest first.
- `yonto-sync-server delete-account <licence id>` drops that account with its records, devices, wraps, join requests and any reset waiting; it exits 1 when there is no such account.
  It is the way out when you have lost every device and the recovery key while a device you don't know still holds the key and syncs, since that device cancels a *Start fresh* (below).
- `yonto-sync-server new-replica` gives the database a new replica id, so every device pushes what it holds again; run it after restoring a backup (*Backups*, below).

All three are safe while the server runs, though a running server goes on answering with the replica id it started with until it starts again.
In Docker, run them inside the container: `docker exec yonto-sync /opt/yonto-sync-server/bin/yonto-sync-server accounts`.

## Starting fresh without the key

Dropping an account needs its key, so nobody who can reach your server can wipe it.
A device that lost the key and the recovery key asks to start fresh instead: the server keeps what it asked for and makes it seven days later, replacing the account in one step, unless a request that proves the key comes first, which cancels it.
So a device that still syncs cancels it at its next sync, and the devices that sync say someone asked.
Yonto's own server waits for an email to the licence's address instead, where the licence has one, which no device can cancel.

## Settings

All are environment variables.

| Variable | Default | What |
|---|---|---|
| `PORT` | `8080` | The port it listens on. |
| `SYNC_DB` | `/data/sync.db` | The SQLite database. Made on first start. |
| `SYNC_ACCOUNTS` | unset: anyone | Licence ids allowed to make an account, comma-separated. |
| `SYNC_MAX_RECORD_BYTES` | `65536` | The largest one encrypted record may be. |
| `SYNC_MAX_ACCOUNT_BYTES` | `33554432` (32 MiB) | All of one account's records together. |
| `SYNC_MAX_ACCOUNT_RECORDS` | `10000` | Records one account may hold, deletions included. |
| `SYNC_MAX_DEVICES` | `20` | Devices on one account's list, not counting removed ones. |
| `SYNC_MAX_BATCH_RECORDS` | `500` | Records in one push or forget. |
| `SYNC_MAX_BODY_BYTES` | `8388608` (8 MiB) | The largest request body. |
| `SYNC_RATE_ACCOUNT` | `600` | Requests per minute per account; `0` turns it off. |
| `SYNC_RATE_ADDRESS` | `1200` | Requests per minute per client address, an IPv6 /64 counting as one; `0` turns it off. |
| `SYNC_BEHIND_PROXY` | `false` | Read the client's address from `X-Forwarded-For` (see above). |
| `YONTO_LICENSING_URL` | unset | Yonto's own instance only: check every request's licence token with the licensing service there. The apps send the token to no other server, so set anywhere else it refuses every device. |
| `YONTO_LICENCE_KEYS` | unset | With `YONTO_LICENSING_URL`, required: the public keys licence tokens are signed with, uncompressed P-256 points in base64, comma-separated. |
| `YONTO_LICENCE_ALLOW_SECONDS` | `86400` | How long the licensing service's yes for a licence is trusted without asking again. |
| `YONTO_LICENCE_REFUSE_SECONDS` | `600` | How long its no is. |
| `YONTO_LICENCE_STALE_SECONDS` | `604800` | While the service is down, how old a yes may be and still count; past it the server answers 503 `unavailable`. |

## Keep it up to date with the apps

The apps and the server speak one revision of the contract (`contracts/sync-v1.md`, *Revisions*), which the server names on every answer.
When Yonto Player updates to a newer revision, a server left on the older one stops working with it: Settings → Sync says the server needs updating, and nothing more is asked of it, joins included, until it is.
A server newer than the app is the same the other way round, so update the server and the apps together.
Rebuild the image (or `installDist`) from the latest release and restart it; the database carries over as it is.

## Backups

Everything is the one SQLite file (with its `-wal` and `-shm` beside it while it runs).
Back it up with `sqlite3 /data/sync.db ".backup /backups/sync.db"`, which is safe while the server runs, or stop the server and copy the file.

To restore one, stop the server, put the copy in place of `sync.db` (deleting any `sync.db-wal` and `sync.db-shm` beside it), run `yonto-sync-server new-replica`, and start the server again.
In Docker, with the container stopped: `docker run --rm -v yonto-sync:/data yonto-sync-server new-replica`.
A copy keeps the replica id it was taken under, so without `new-replica` your devices would keep the place they had reached in it, skip records it takes from then on, and never push again what it lacks.
With it, and on a new database, which gets a new replica id of its own, they notice and push everything they hold again, so a lost database costs nothing but that upload.
A deletion older than 90 days is forgotten by the server (except a source's, which it keeps for good), which is why devices away longer than that reconcile rather than just catch up.

## Develop

```sh
./gradlew test
```

The tests run the server over HTTP against the contract's vectors (`contracts/sync-v1.json`, found by walking up from the working directory) and a fake licensing service.

## Licence

MIT, see [LICENSE](LICENSE).
