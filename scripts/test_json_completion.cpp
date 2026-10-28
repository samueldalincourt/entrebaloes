#include "../android/app/src/main/cpp/json_completion.h"

#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
void require(bool condition, const std::string & message) {
    if (!condition) throw std::runtime_error(message);
}
}

int main() {
    const std::vector<std::string> valid = {
        "{}", " \r\n{\t\"b0\": \"Olá!\"}\n",
        R"({"b0":"A } dentro da fala não fecha o objeto.","b1":"{ também não."})",
        R"({"b0":"Ela disse: \"vá!\"","b1":"Uma barra: \\"})",
        R"({"b0":"\u00e3 e \uD83D\uDE00","b1":"\b\f\n\r\t\/"})",
        R"({"b0":"","":"Uma chave vazia é JSON estruturalmente válido."})",
    };
    for (const auto & output : valid) {
        require(webtoon::json_object_complete(output), "Complete valid output rejected: " + output);
        // Every possible token boundary, including inside escapes and UTF-8.
        for (std::size_t split = 0; split <= output.size(); ++split) {
            webtoon::JsonCompletion scanner;
            scanner.feed(std::string_view(output).substr(0, split));
            scanner.feed(std::string_view(output).substr(split));
            require(scanner.complete() && !scanner.invalid(), "Token boundary changed completion");
        }
        webtoon::JsonCompletion scanner;
        const auto closing_brace = output.find_last_of('}');
        for (std::size_t i = 0; i < output.size(); ++i) {
            const bool complete = scanner.feed(std::string_view(output).substr(i, 1));
            // Test fixtures put the actual root terminator last, after any
            // braces and escaped quotes embedded in the dialogue strings.
            require(complete == (i >= closing_brace), "Premature or missing completion");
        }
        require(scanner.complete(), "Byte-at-a-time output not complete");
    }
    const std::vector<std::string> incomplete = {
        "", "  ", "{", "{\"b0\"", "{\"b0\":", "{\"b0\":\"fala}",
        "{\"b0\":\"barra\\", "{\"b0\":\"\\u00", "{\"b0\":\"fim\"", "{\"b0\":\"fim\",",
    };
    for (const auto & output : incomplete) {
        webtoon::JsonCompletion scanner;
        require(!scanner.feed(output) && !scanner.invalid(), "Truncated output accepted or marked corrupt");
    }
    const std::vector<std::string> invalid = {
        "}", "[]", "{}{}", "{}garbage", R"({"b0"})", R"({"b0":})",
        R"({"b0":"fim",})", R"({"b0":"fim"])", R"({"b0":"\q"})",
        R"({"b0":"\u00xz"})", "{\"b0\":\"linha\ncrua\"}",
        R"({"b0":123})", R"({"b0":{"nested":"fora da gramática"}})",
    };
    for (const auto & output : invalid) {
        webtoon::JsonCompletion scanner;
        require(!scanner.feed(output) && scanner.invalid(), "Malformed grammar output accepted: " + output);
    }
    std::cout << "JSON completion: valid, truncated, malformed and all token-boundary cases passed\n";
}
