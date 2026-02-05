# Miscellaneous Configuration

Additional configuration options that don't fit into other categories.

## Options

```toml
[misc]
show_processing_message = true
clear_chat_on_join = false
welcome_message_padding = 2
```

### `show_processing_message`

| | |
|---|---|
| **Type** | Boolean |
| **Default** | `true` |

When enabled, shows a "Processing..." message while async operations (password hashing, database queries) are in progress. This provides feedback to the player that their action is being handled.

### `clear_chat_on_join`

| | |
|---|---|
| **Type** | Boolean |
| **Default** | `false` |

When enabled, clears the player's chat history when they join the server, before showing the welcome/login message. This can help keep the authentication prompt clean and visible.

### `welcome_message_padding`

| | |
|---|---|
| **Type** | Integer |
| **Default** | `2` |

Number of empty lines added before the welcome message in chat. Helps visually separate the authentication prompt from other server messages.
