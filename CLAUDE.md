# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a production-ready Velocity proxy plugin designed to manage Google Cloud Platform Compute Engine instances for Minecraft servers, enabling automatic startup/shutdown while maintaining 24/7 proxy availability. The plugin is built using Java 17 and targets the Velocity proxy version 3.4.0-SNAPSHOT.

**Status**: Production Ready | Public Release Ready | Fully Tested

## Build System

The project uses Gradle with several key features:

- **Java Version**: Java 17 (required)
- **Velocity API**: 3.4.0-SNAPSHOT from PaperMC repository
- **Shadow Plugin**: Bundles dependencies into the final JAR with relocation to avoid conflicts
- **Build Template System**: Version numbers are injected at build time via template expansion

### Dependencies

- **Google Cloud Compute API**: `com.google.cloud:google-cloud-compute:1.54.0` - For GCP instance management
- **SnakeYAML**: `org.yaml:snakeyaml:2.2` - For YAML configuration parsing
- **OkHttp**: `com.squareup.okhttp3:okhttp:4.12.0` - For HTTP requests (Mojang API)
- **Gson**: `com.google.code.gson:gson:2.10.1` - For JSON parsing

All dependencies are shaded and relocated to `io.github.finn2409.velocityGcpController.libs.*` to prevent conflicts.

**Important Note**: `com.google.inject` (Guice) is NOT relocated - it's required by Velocity for dependency injection.

### Common Commands

```bash
# Build the plugin (produces shaded JAR)
./gradlew build

# Clean and rebuild
./gradlew clean build

# Run Velocity with the plugin for testing
./gradlew runVelocity

# Build without daemon (for CI/CD)
./gradlew build --no-daemon
```

The final JAR will be located at `build/libs/velocity-gcp-controller-1.0-SNAPSHOT.jar` (~41MB with shaded dependencies).

## Architecture

### Modular Design

The plugin uses a modular architecture where features can be individually enabled/disabled:

```
VelocityGcpController (Main Class)
├── PluginConfig - Configuration management
└── Modules/
    ├── GcpModule - Google Cloud Platform integration
    ├── IdleManagementModule - Player tracking and shutdown timers
    ├── WhitelistModule - Custom proxy-level whitelist
    └── CommandsModule - /ping and /vwhitelist commands
```

### Module System

All modules implement the `Module` interface:
- `initialize()` - Called during plugin startup
- `shutdown()` - Called during plugin shutdown
- `getName()` - Returns module name for logging
- `isEnabled()` - Checks if module is enabled in config

### Configuration System (`config/PluginConfig.java`)

- Loads from `config.yml` in the plugin data directory
- Creates default config with inline comments if none exists
- Custom formatting matches `config.example.yml` structure
- Validates configuration with critical error checking
- Provides warnings for non-critical misconfigurations
- Default values: `instance-name: "minecraft-server"`, `server-name: "lobby"`

### GCP Module (`modules/GcpModule.java`)

Key features:
- **Status Caching**: Caches GCP instance status for configurable duration (default 10s)
- **Startup Cooldown**: Prevents duplicate start commands within cooldown period (default 5min)
- **Port Health Check**: Verifies Minecraft server is reachable, not just GCP instance
- **Async Operations**: All GCP commands return `CompletableFuture<Boolean>`
- **Increased Timeouts**: 90 seconds for start/stop operations (prevents timeout errors)

Authentication methods:
1. Default service account (recommended for GCP deployments)
2. JSON key file (for local development or non-GCP environments)

Supported GCP Roles:
- **Editor** role (simplest, includes all permissions)
- **Compute Instance Admin (v1)** role (more restrictive)
- Custom role with minimal permissions (most restrictive)

### Idle Management Module (`modules/IdleManagementModule.java`)

Manages two critical timers:
- **Idle Shutdown Timer**: Starts when player count reaches 0, stops instance after configurable period
- **Startup Timeout Timer**: Prevents orphaned startups - shuts down instance if no one joins after startup

**Player Tracking System**:
- Uses `AtomicInteger` for thread-safe player counting
- Maintains a `ConcurrentHashMap.newKeySet()` to track connected players by UUID
- Prevents duplicate increments/decrements when the same player triggers multiple events
- Critical for handling backend disconnects where `getCurrentServer()` returns empty
- `onPlayerJoin(UUID)` and `onPlayerLeave(UUID)` verify player state before modifying count

### Whitelist Module (`modules/WhitelistModule.java`)

- Uses Minecraft's standard `whitelist.json` format
- Operates at proxy level (before backend connection)
- Thread-safe operations with immediate file persistence
- Stores both UUID and player name for better display
- Creates example entry on first initialization

### Commands Module (`modules/CommandsModule.java`)

**`/ping` Command**:
- Available to all players
- Color-coded latency display (green=good, red=bad)

**`/vwhitelist` Commands**:
- `add <player>` - Validates via Mojang API before adding
- `remove <player>` - Removes from whitelist (with tab completion)
- `list` - Shows player names with UUIDs (e.g., "PlayerName (uuid)")
- Tab completion for subcommands and player names
- Restricted to UUIDs in `authorized-uuids` config list

### Connection Flow

The plugin hooks into Velocity events to manage the lifecycle:

1. **PreLoginEvent** - Whitelist check (denies before proxy connection)
2. **ServerPreConnectEvent** - GCP startup logic (checks if backend is available, disconnects player with message)
3. **ServerConnectedEvent** - Player count tracking (increment, cancel shutdown)
4. **DisconnectEvent** - Player count tracking (decrement, start idle timer)

**Important**: When backend is offline, players are disconnected from proxy with `player.disconnect()` to show the configured startup message properly (not just denied server connection).

### Template System

The project uses Gradle's template expansion to inject build-time constants:
- Template source: `src/main/templates/`
- Generated output: `build/generated/sources/templates/`
- The `BuildConstants.java` template file has `${version}` replaced with the project version at compile time
- The `generateTemplates` task runs automatically before compilation

## Package Organization

```
io.github.finn2409.velocityGcpController/
├── VelocityGcpController.java - Main plugin class
├── config/
│   └── PluginConfig.java - Configuration loader and validator
├── modules/
│   ├── Module.java - Module interface
│   ├── GcpModule.java - GCP Compute Engine integration
│   ├── WhitelistModule.java - Whitelist management
│   ├── IdleManagementModule.java - Timer management
│   └── CommandsModule.java - Command handlers
└── util/
    └── MessageFormatter.java - Legacy color code converter
```

## Development Notes

- **Plugin ID**: `velocity-gcp-controller`
- **Version**: Managed in `build.gradle` as `1.0-SNAPSHOT`
- **Encoding**: UTF-8 enforced for all Java compilation
- **Working Directories**: `run/` and `runs/` for testing (gitignored)
- **Logging Prefix**: All log messages use `[VelocityGCPController]` prefix

## Configuration

The plugin creates a default `config.yml` on first run. Key sections:

- **modules**: Enable/disable individual features
- **gcp**: GCP project, instance, zone, and authentication settings
- **timers**: Idle shutdown and startup timeout durations
- **backend**: Backend server address, port, and name (must match velocity.toml)
- **whitelist**: Whitelist file path and kick message
- **commands**: Authorized UUIDs for /vwhitelist commands
- **messages**: Customizable player-facing messages (supports & color codes)
- **logging**: Granular logging control for different event types

## Important Implementation Details

1. **Thread Safety**: Player counting uses `AtomicInteger`, GCP operations are async
2. **Error Handling**: All module initialization errors are caught and logged
3. **Graceful Shutdown**: All timers are cancelled during plugin shutdown
4. **Cache Invalidation**: GCP status cache is invalidated after start/stop operations
5. **Cooldown Logic**: Start commands respect cooldown even across multiple player attempts
6. **Guice Compatibility**: Guice is NOT relocated to maintain Velocity dependency injection compatibility
7. **Player Disconnection**: Uses `player.disconnect()` to show custom messages properly
8. **Config Formatting**: Custom YAML writer with inline comments (not SnakeYAML dump)

## Version History & Fixes

### v4 (Current - October 2025)
- ✅ Fixed player count desynchronization when backend disconnects players
- ✅ Implemented UUID-based player tracking system in IdleManagementModule
- ✅ DisconnectEvent now uses tracking set instead of getCurrentServer() check
- ✅ Prevents player count from incrementing indefinitely on backend kicks/timeouts

### v3 (October 2025)
- ✅ Fixed player disconnect message (shows proper startup message instead of "internal error")
- ✅ Added all inline comments to generated config.yml
- ✅ Updated default values (instance-name, server-name, startup message timing)
- ✅ Updated GCP permissions documentation (Editor role support)

### v2 (October 2025)
- ✅ Added tab completion for `/vwhitelist` command
- ✅ Whitelist stores and displays player names
- ✅ Config file formatting improved with section comments
- ✅ Example whitelist entry on first creation
- ✅ GCP timeout increased from 30s to 90s

### v1 (October 2025)
- ✅ Fixed Guice injection error (selective package relocation)
- ✅ Initial release with all core features

## Repository Organization

### Installation Priority
- **README.md** prioritizes end users (download from releases)
- Building from source is secondary (developer section)
- Clear separation of user vs developer documentation

### .gitignore Protection
Protects against committing:
- GCP credentials (*.json, *.key, *.pem)
- User configs (config.yml, whitelist.json)
- Log files (*.logs, velocityproxy.logs)
- Test directories (test/, temp/, tmp/)
- Environment files (.env, credentials.*)

### Documentation Files
- **README.md** - User-facing documentation (installation, configuration, usage)
- **CLAUDE.md** - This file (developer/AI guidance)
- **CONTRIBUTING.md** - Contribution guidelines
- **config.example.yml** - Example configuration with comments
- **LICENSE** - MIT License

## Testing Considerations

- The plugin requires a valid GCP project for full testing
- Use `modules.gcp: false` to test other features without GCP
- Whitelist can be tested independently
- Commands module requires at least one authorized UUID
- Backend server name in config must match a server defined in `velocity.toml`
- VM needs read/write Compute Engine API access configured

## Known Issues & Solutions

### Issue: Plugin won't load - Guice injection error
**Solution**: Ensure Guice is NOT relocated. Shadow plugin must use selective relocation.

### Issue: Players get "internal error" instead of startup message
**Solution**: Use `player.disconnect()` instead of `event.setResult() + sendMessage()`.

### Issue: GCP commands timeout after 30 seconds
**Solution**: Increase timeout to 90 seconds for start/stop operations.

### Issue: Config file poorly formatted
**Solution**: Use custom StringBuilder formatting with inline comments, not SnakeYAML dump.

### Issue: Player count desynchronizes when backend kicks/disconnects players
**Solution**: Use UUID-based tracking set instead of relying on `getCurrentServer()` in DisconnectEvent, since it returns empty when players are kicked by backend.

## Production Readiness Checklist

- ✅ All modules tested and working
- ✅ Tab completion implemented
- ✅ Player messages display correctly
- ✅ Config formatting matches example
- ✅ GCP timeouts adequate for operations
- ✅ Whitelist stores player names
- ✅ Documentation complete and accurate
- ✅ .gitignore protects sensitive data
- ✅ README prioritizes end users
- ✅ Repository organized for public release

**Status**: Production Ready for v1.0.0 Release