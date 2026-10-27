# Initial
We are going to build a front-end app for polaris-assistant. 
The app should support following features: 
- User can chat to ask about a product, place an order, see more detail in the @intents.json
- The product information, order draft to be confirming should display in product card or order card like a popup when user click on the product/order link for more detail. 

# Initial Design
We choose to follow a guide here https://martinfowler.com/articles/micro-frontends.html
Tech-stack for assistant app is: vuejs
the app composer hostname: app.polaris.local

# Plan
We will implement in multiple slices with trunk-base approaches: 
- Slice-1: initial micro-frontend infrastructure, just a hello world app for assistant
- Slice-2: sharing auth component
- Slice-3: simple chat turn with send request/response
- Other slices: TBD
