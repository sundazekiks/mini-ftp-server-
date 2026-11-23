# Simple File Server

A small FTP-style file server and client in Java. The server listens on a control port (default `9090`) and opens a temporary passive data port for each file transfer.

## Requirements

- Java 17 or newer
- Server and client on the same network (LAN)

## Project layout

| File | Purpose |
|------|---------|
| `Server.java` | Accepts client connections |
| `FileTransferController.java` | Handles commands (`LIST`, `PASV`, `STOR`, `DWNLD`, `CMDS`) |
| `ServerC.java` | Interactive command-line client |
| `root_share/` | Folder the server shares. Clients can't access anything outside it |

---

## Running the server

### 1. Compile

```bash
javac *.java
```

### 2. Prepare the shared folder

The server shares `root_share/` relative to the directory it is started from.

```bash
mkdir -p root_share
```

The user running the server needs read and write access to `root_share/` and to the directory containing the compiled `.class` files.

### 3. Open the firewall (Linux / ufw)

Allow the control port and the passive data ports from your LAN only:

```bash
sudo ufw allow from 192.168.8.0/24 to any port 9090 proto tcp
sudo ufw allow from 192.168.8.0/24 to any port 50000:50010 proto tcp
```

Replace `192.168.8.0/24` with your own subnet. Check the rules with `sudo ufw status numbered`.

> If the server still uses random passive ports (`new ServerSocket(0)`), the range above won't apply. Either switch to the fixed range or allow all ports from your client's IP: `sudo ufw allow from <client-ip>`.

### 4. Start

```bash
java -Djava.net.preferIPv4Stack=true Server
```

**Why the flag?** The JVM may prefer IPv6. The `PASV` reply must contain an IPv4 address (`h1,h2,h3,h4,p1,p2`), so this flag forces the server to use IPv4.

---

## Running the client

The client takes the server address as arguments, so you don't need to edit the source:

```bash
java ServerC <server-ip> [port]
# example
java ServerC 192.168.8.130 9090
```

If you omit the arguments, it uses the defaults hardcoded in `ServerC.main`.

You don't need to type `Start` with this client.

### Client commands

| Command | Description |
|---------|-------------|
| `list` | List files on the server (`/name` = folder, `-name` = file) |
| `cmds` | Show commands the server supports |
| `stor <local> [remoteName]` | Upload a file |
| `dwnld <remote> [local]` | Download a file |
| `help` | Show help |
| `quit` | Disconnect |

File names can't contain spaces. Downloads are saved to a `.part` file first and renamed once complete. The client will not overwrite an existing local file.

---

## Troubleshooting

| Symptom | Likely cause |
|---------|--------------|
| `Connect timed out` on startup | Wrong IP, server not running, or port 9090 blocked |
| `cmds` works but `list`/`stor`/`dwnld` time out | Passive data port blocked by the firewall (or not forwarded in Docker/VM/WSL) |
| `425 Use PASV first` | Data command was sent without a passive port open |
| `550 File not found or not readable` | Wrong file name, or no read permission |
| Garbled or `Unexpected PASV reply` | Server started without `-Djava.net.preferIPv4Stack=true` |