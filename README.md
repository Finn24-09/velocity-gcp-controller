# Velocity GCP Controller

A Velocity proxy plugin designed to manage Google Cloud Platform Compute Engine instances for Minecraft servers, enabling automatic startup/shutdown while maintaining 24/7 proxy availability.

## Overview

This plugin allows you to run a Velocity proxy 24/7 while your resource-intensive backend Minecraft server (like modpacks such as "All the Mods 10 to the Sky") only runs when players are actually online.

### Key Features

- **Automatic Instance Management**: Backend server starts when players connect, stops after idle period
- **Smart Timers**: Idle shutdown and orphaned startup protection
- **Proxy-Level Whitelist**: Control who can trigger server startups
- **Custom Commands**: `/ping` for latency and `/vwhitelist` for whitelist management
- **Modular Architecture**: Enable/disable features as needed

## How It Works

1. **Player Connects**: Velocity proxy is always online
2. **Whitelist Check**: If enabled non-whitelisted players are denied at the proxy level
3. **Backend Check**: Plugin checks if backend server is running
4. **Auto-Start**: If offline, plugin starts the GCP instance
5. **Startup Message**: Player is kicked with "Server starting, reconnect in ..."
6. **Player Joins**: When ready, player reconnects and joins normally
7. **Idle Shutdown**: When all players leave, timer starts (default 30 min)
8. **Auto-Shutdown**: If no one returns, server shuts down automatically

## Requirements

### Prerequisites

- **Java 17+** for building and running
- **Gradle** (or use the wrapper - `./gradlew`)
- **Velocity Proxy** 3.4.0-SNAPSHOT or compatible version
- **Google Cloud Platform** account with:
  - Compute Engine instance for backend Minecraft server
  - Service account with instance management permissions

### GCP Permissions Required

The service account needs these permissions:
- `compute.instances.start`
- `compute.instances.stop`
- `compute.instances.get`

**Recommended Role Options:**
- **Editor** role (simplest, includes all required permissions)
- **Compute Instance Admin (v1)** role (more restrictive)
- Custom role with only the three permissions listed above (most restrictive)

**Note**: The proxy compute instance must have explicit read/write API access to the Compute Engine API configured.

## Installation

### Using release (Recommended)

1. **Download the latest release** from [GitHub Releases](https://github.com/Finn24-09/velocity-gcp-controller/releases)
2. **Copy the JAR** to your Velocity proxy's `plugins/` folder
3. **Start Velocity** to generate default config files
4. **Configure the plugin** (see [Configuration](#configuration) section)
5. **Restart Velocity** to apply settings

### Building from Source

#### Clone the Repository

```bash
git clone https://github.com/Finn24-09/velocity-gcp-controller.git
cd velocity-gcp-controller
```

#### Generate Gradle Wrapper (if needed)

If `gradlew` scripts are missing:

```bash
gradle wrapper
```

#### Build the Plugin

```bash
# On Linux/Mac
./gradlew build

# On Windows
gradlew.bat build
```

The compiled JAR will be located at:
```
build/libs/velocity-gcp-controller-*.jar
```

## Configuration

### Location

The plugin creates configuration files in `plugins/velocity-gcp-controller/`:
- `config.yml` - Main configuration
- `whitelist.json` - Whitelisted players (if enabled)

### Configuration Options

#### Modules Section

Enable or disable features:
- `gcp`: GCP instance management
- `idle-management`: Automatic shutdown timers (requires `gcp: true`)
- `whitelist`: Proxy-level whitelist system
- `commands`: Enable `/ping` and `/vwhitelist` commands

**Important**: `gcp` and `idle-management` must both be true or both be false.

## Velocity Configuration

The plugin references a server defined in Velocity's `velocity.toml`. Make sure your backend server is registered:

```toml
[servers]
lobby = "localhost:25565"
```

The name `lobby` must match the `backend.server-name` setting in the plugin config.

## GCP Setup

### Option 1: Service Account (Recommended)

If your Velocity proxy runs on GCP:

1. Create a service account in GCP Console
2. Grant it the **Editor** role or **Compute Instance Admin (v1)** role
3. Attach the service account to your Velocity proxy VM
4. Ensure the VM has read/write API access to Compute Engine
5. Set `use-service-account: true` in config

### Option 2: JSON Key File

For local development or non-GCP environments:

1. Create a service account in GCP Console
2. Grant it the **Editor** role or **Compute Instance Admin (v1)** role
3. Create and download a JSON key
4. Place the JSON key somewhere secure
5. Set `use-service-account: false` in config
6. Set `service-account-key` to the path of the JSON file
7. **Never commit the JSON key to version control!**

## Commands

### /ping

Available to all players.

```
/ping
```

Shows your latency with color-coded health indicator:
- 0-50ms: Dark Green - Excellent
- 51-100ms: Green - Good
- 101-150ms: Yellow - Fair
- 151-200ms: Gold - Poor
- 201-300ms: Red - Bad
- 301+ms: Dark Red - Very Bad

### /vwhitelist

Available only to authorized UUIDs (configured in `authorized-uuids`).

```
/vwhitelist add <playername>
/vwhitelist remove <playername>
/vwhitelist list
```

- `add`: Adds a player to the whitelist (validates via Mojang API)
- `remove`: Removes a player from the whitelist
- `list`: Shows all whitelisted player UUIDs

## Whitelist Management

The whitelist uses Minecraft's standard format (`whitelist.json`):

```json
[
  {
    "uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5",
    "name": "Unknown"
  }
]
```

You can edit this file manually or use `/vwhitelist` commands in-game.

**Note**: The backend server's whitelist should be disabled - this plugin handles whitelisting at the proxy level.

### Plugin Won't Load

- Check that you're running **Java 17+**
- Verify Velocity version is **3.4.0-SNAPSHOT** or compatible
- Check logs for configuration validation errors
- Ensure you're using the latest build (Guice injection fix included)

### GCP Commands Failing

- Verify service account has required permissions
- Check that `project-id`, `instance-name`, and `zone` are correct
- Test GCP credentials manually with `gcloud` CLI
- Check firewall rules allow proxy to reach GCP API

### Backend Server Won't Start

- Verify instance name and zone in config
- Check GCP Console to see if instance is responding
- Look for "startup cooldown" messages in logs
- Increase `startup-timeout-minutes` for heavy modpacks

### Players Can't Connect

- Verify server name in config matches `velocity.toml`
- Check backend server is actually ready (not just instance running)
- Test direct connection to backend server
- Review whitelist configuration

### Timers Not Working

- Ensure `idle-management: true` in modules section
- Check that player count tracking is working (look for logs)
- Verify timer values are appropriate for your use case

### Project Structure

```
src/main/java/io/github/finn2409/velocityGcpController/
├── VelocityGcpController.java          # Main plugin class
├── config/
│   └── PluginConfig.java               # Configuration management
├── modules/
│   ├── Module.java                     # Module interface
│   ├── GcpModule.java                  # GCP integration
│   ├── WhitelistModule.java            # Whitelist management
│   ├── IdleManagementModule.java       # Timer management
│   └── CommandsModule.java             # Command handlers
└── util/
    └── MessageFormatter.java           # Message utilities
```

### Dependencies

- **Velocity API**: 3.4.0-SNAPSHOT
- **Google Cloud Compute**: 1.54.0
- **SnakeYAML**: 2.2
- **OkHttp**: 4.12.0
- **Gson**: 2.10.1

All dependencies are shaded and relocated to prevent conflicts.

## Known Limitations

1. **Startup Time**: Heavy modpacks may take 2-5 minutes to start
2. **GCP API Latency**: Connection attempts add 100-300ms latency for status checks
3. **Status Cache**: Briefly may show stale data (configurable duration)
4. **Concurrent Startups**: Multiple players may get kicked during startup cooldown

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Links

- **Velocity Documentation**: https://docs.papermc.io/velocity
- **Google Cloud Compute Engine**: https://cloud.google.com/compute
- **Project Repository**: https://github.com/Finn24-09/velocity-gcp-controller
