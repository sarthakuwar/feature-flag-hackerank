# Requirements

## Overview

Build a LaunchDarkly-inspired feature flag app that helps teams create, target, roll out, and measure feature flags. Think beyond a list of on/off toggles.

The app is full-stack React + Spring Boot (Java). It keeps the React frontend and Spring Boot backend of the sample calendar repo you clone, and it persists data in MongoDB. Every feature you ship must work end to end: a usable screen, a real API call, a backend handler, and stored data.

Pick five to ten features from the list below, or add your own of the same weight. Fewer than five is under scope. More than ten spreads the work too thin.

## Feature set

- **Feature flag management.** Create, configure, activate, and retire feature flags.
- **Environment-based configuration.** Manage flags independently across development, staging, and production.
- **User and segment targeting.** Release features to specific users or audience groups.
- **Progressive rollouts.** Gradually increase feature availability across users.
- **Approval workflows.** Review, approve, and schedule flag changes.
- **Experimentation and metrics.** Compare feature variations and measure their impact.
- **Change history and rollbacks.** Track changes and restore previous flag configurations.

## Acceptance criteria

- **Five to ten features**, each working end to end through the UI, the API, and MongoDB.
- **Human judgment is visible.** AI can write the code. Feature selection, architecture, and production readiness must be your own decisions, and the transcripts should show it.
- **Modern, polished UI.** Responsive, keyboard accessible, with loading, empty, validation, and error states where they apply.
- **Clean checkout works.** The app installs, builds, and starts without errors using the commands in `hackerrank.yml`.
- **Current stack.** Dependency versions are at least as recent as the sample calendar repo. Do not downgrade, and do not swap out the declared stack.
- **Matches the sample repo in scope, structure, and quality.** Use the calendar app as the bar for what "done" looks like.
