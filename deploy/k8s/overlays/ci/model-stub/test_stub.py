import unittest

import stub


def user(text):
    return [{"role": "user", "parts": [{"text": text}]}]


class StubTest(unittest.TestCase):
    def test_search_turn_calls_the_search_tool_with_the_keyword(self):
        part = stub.gemini_reply(user("Find products matching E2E"))[0]
        self.assertEqual(part["functionCall"], {"name": "search_available_products", "args": {"query": "E2E"}})

    def test_order_turn_stages_a_draft_for_the_sku(self):
        part = stub.gemini_reply(user("I want to order 1 unit of E2E-UNLIMITED-01 for myself"))[0]
        self.assertEqual(part["functionCall"]["name"], "stage_order_draft")
        self.assertEqual(part["functionCall"]["args"]["items"], [{"sku": "E2E-UNLIMITED-01", "quantity": 1}])

    def test_status_turn_names_the_order(self):
        text = stub.gemini_reply(user("What is the status of order ORD-123?"))[0]["text"]
        self.assertIn("ORD-123", text)

    def test_cancel_turns_reply_in_text(self):
        for message in ("Please cancel order ORD-1", "Yes, confirm the cancellation"):
            self.assertIn("text", stub.gemini_reply(user(message))[0])

    def test_tool_result_gets_a_text_reply(self):
        contents = user("Find products matching E2E") + [
            {"role": "user", "parts": [{"functionResponse": {"name": "x", "response": {}}}]}]
        self.assertIn("text", stub.gemini_reply(contents)[0])

    def test_intents_follow_the_turn(self):
        cases = {
            "Find products matching E2E": stub.SEARCH,
            "I want to order 1 unit of X for myself": stub.PLACE,
            "What is the status of order ORD-1?": stub.STATUS,
            "Please cancel order ORD-1": stub.CANCEL,
            "Yes, confirm the cancellation": stub.CANCEL,
            "hello": stub.GENERAL,
        }
        for message, intent in cases.items():
            body = {"state": {"message": message}}
            self.assertEqual(stub.systemone_reply(body)["answers"]["intent"]["choice"], intent, message)


if __name__ == "__main__":
    unittest.main()
