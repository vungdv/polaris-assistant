# Tests Improvement

- **Scope:** the automated test suite of every Maven module (`libs/*`, `apps/*`) and its CI pipeline
- **Nature:** strategy plan. Objective and high-level requirement 
- **Status:** Draft for review

## 1. Problem statement
We're all know about test pyramid, however we lack of a pragmatic/pratical guideline or guard to ensure the implementation is effective.
That leads to a situation in our test suite today: 
- Time-consuming: over 10 minutes to execute tests, it hurt our performance in both human & code agent
- Unit-test and integration-test are mixing, the complexity is increasing, then the cost of the development & maintenance, untrusted verification.

We're all know a guideline here https://martinfowler.com/articles/practical-test-pyramid.html however it's too wide to be a reference. 
When writing a test, people normally focus on their own problem, however the problem come from a sum of all the works, and it's not something pop-up in just a day.

So we need a guideline that drives from https://martinfowler.com/articles/practical-test-pyramid.html but focus on this project. The guidelines should be:
- Be intentional about test scope. Example for a class, each test should focus on single reason to failed. It should classify into core behavior, then defensive, edge cases. Sociable unit-test should be narrow and stable test-case.
- Provide a guard for the guideline, any problem should link back to the guideline.
