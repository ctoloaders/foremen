@smoke @FOR-04
Feature: Projects — list and create happy path
  # FOR-04 base-entities smoke coverage (Requirements 8.4, 4.1, 9.2).
  # The Projects page renders the list; creating a project via the happy path
  # (name only) makes it appear in the list. The created project is deleted in
  # teardown (via the same DELETE /api/projects/{id} the UI uses), so the run
  # stays self-cleaning (Requirement 3). This create -> verify -> delete
  # lifecycle transitively exercises the FOR-01 CRUD framework (Requirement 9.2).

  Background:
    Given the application stack is ready
    And I am logged in as the seeded admin

  Scenario: The projects list renders and a created project appears in it
    When I open the Projects page
    Then the projects list renders
    When I create a project via the happy path
    Then the created project appears in the projects list
