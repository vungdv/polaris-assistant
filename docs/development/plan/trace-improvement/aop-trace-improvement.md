# Problem statement
For a production grade there are a lot of requirement type beside business logic:
- Resilient, fault-tolerant
- Observability

If the system enable intelligent by adding AI-agent component then we have more requirement for
- Intent log and trace
- Token Usage

The combination all of them make the system or even just a single function become too complex to be understandable/managable

# Approach
There are several approaches like aspect oriented programming, behavioral patterns like strategy pattern, 
for all of them the core idea is the decomposition to build single responsibility (SRP).

With observability, we sometime need to log/tag the state of a processing function/component which might lead the violation of SRP
so to avoid this we need design code to avoid that.

## Trace on Input/Output
For a given component/function we have
Output = f(Input) 
We shouldn't change the f, we can apply one of the following approaches: 
- AOP, to dynamic create proxy/wrapper of the f, then log/tag trace or metric there
- f might depend on other like f1, f2 if we want to observe them we should apply the AOP on f1, f2 directly. If we are not manage f1, f2 we can create wrapper and apply AOP there.

Example: In the class AssistantChatService we have several violation of SRP by inline the observability
- tagResolvedIntent
- tagIterationsCount
