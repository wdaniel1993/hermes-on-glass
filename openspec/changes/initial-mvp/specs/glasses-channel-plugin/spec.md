## ADDED Requirements

### Requirement: Plugin registers as a Hermes channel for the glasses

The glasses-channel-plugin SHALL be installable via Hermes' plugin discovery mechanism (`~/.hermes/plugins/glasses-channel/`) and SHALL register as a channel that Hermes' delivery layer (cron jobs, scheduled tasks, agent-initiated nudges) can target.

#### Scenario: Plugin install

- **WHEN** the user installs the plugin into `~/.hermes/plugins/glasses-channel/` and restarts the Hermes gateway
- **THEN** `hermes channels list` (or equivalent) shows `glasses` as an available delivery target

#### Scenario: Cron delivery

- **WHEN** a Hermes cron job is configured with delivery target `glasses` and fires
- **THEN** the plugin receives the message via the channel-adapter API and forwards it to the configured phone-app webhook

### Requirement: Plugin forwards messages to the phone-app via authenticated webhook

The plugin SHALL forward each Hermes-originated message as an HTTP POST to a user-configured phone-app webhook URL, including a shared-secret bearer token to authenticate the push.

#### Scenario: Successful forward

- **WHEN** the plugin receives a message and the phone-app webhook returns 200
- **THEN** the plugin marks the message as delivered and emits no retry

#### Scenario: Webhook unreachable

- **WHEN** the phone-app webhook returns a 5xx status or fails to connect
- **THEN** the plugin retries with exponential backoff up to 3 attempts and then logs a delivery failure

#### Scenario: Authentication

- **WHEN** the plugin sends a forward
- **THEN** the request carries an `Authorization: Bearer <plugin-shared-secret>` header

### Requirement: Plugin payload schema preserves message origin and trigger

The plugin SHALL send a JSON body that distinguishes message origin (`cron-job`, `run-completion`, `agent-nudge`) and includes the originating session/conversation name and a stable message id.

#### Scenario: Cron-origin payload

- **WHEN** a scheduled cron job triggers a delivery
- **THEN** the forwarded payload includes `{ "origin": "cron-job", "jobId": "<id>", "conversation": "<name>", "messageId": "<uuid>", "text": "<rendered>" }`

#### Scenario: Run-completion payload

- **WHEN** a long-running `/v1/runs` task completes and is configured to notify the glasses
- **THEN** the forwarded payload includes `{ "origin": "run-completion", "runId": "<id>", "conversation": "<name>", "messageId": "<uuid>", "text": "<output>" }`

### Requirement: Plugin is configurable via a single YAML file

The plugin SHALL read its configuration from `~/.hermes/plugins/glasses-channel/config.yaml`, including `webhook_url`, `shared_secret`, and `default_conversation`.

#### Scenario: Missing config

- **WHEN** the plugin loads with no config file
- **THEN** the plugin logs a configuration error, does not register the channel, and the Hermes gateway continues to start
