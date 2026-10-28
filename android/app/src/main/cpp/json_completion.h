#pragma once

#include <string_view>

namespace webtoon {

// Incremental recognizer for the JNI output grammar: a JSON object of strings.
// Feed whole token pieces, which can split escapes and UTF-8 characters. A
// complete object can stop inference before the final token's decode/EOG pass.
// Values and IDs still need the existing caller-side semantic validation.
class JsonCompletion {
public:
    bool feed(std::string_view chunk) {
        for (unsigned char c : chunk) {
            if (state_ == State::invalid) break;
            if (state_ == State::key_string || state_ == State::value_string) {
                consume_string(c);
                continue;
            }
            if (whitespace(c)) continue;
            switch (state_) {
                case State::start:
                    state_ = c == '{' ? State::key_or_end : State::invalid;
                    break;
                case State::key_or_end:
                    state_ = c == '}' ? State::done : c == '"' ? State::key_string : State::invalid;
                    break;
                case State::key_required:
                    state_ = c == '"' ? State::key_string : State::invalid;
                    break;
                case State::colon:
                    state_ = c == ':' ? State::value_start : State::invalid;
                    break;
                case State::value_start:
                    state_ = c == '"' ? State::value_string : State::invalid;
                    break;
                case State::comma_or_end:
                    state_ = c == '}' ? State::done : c == ',' ? State::key_required : State::invalid;
                    break;
                case State::done:
                    state_ = State::invalid; // Two roots or other trailing data.
                    break;
                default:
                    state_ = State::invalid;
                    break;
            }
        }
        return complete();
    }

    bool complete() const { return state_ == State::done; }
    bool invalid() const { return state_ == State::invalid; }

private:
    enum class State { start, key_or_end, key_required, key_string, colon,
                       value_start, value_string, comma_or_end, done, invalid };
    State state_ = State::start;
    bool escaped_ = false;
    unsigned unicode_remaining_ = 0;

    static bool whitespace(unsigned char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }
    static bool hexadecimal(unsigned char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
    void consume_string(unsigned char c) {
        if (unicode_remaining_) {
            if (!hexadecimal(c)) state_ = State::invalid;
            else --unicode_remaining_;
            return;
        }
        if (escaped_) {
            escaped_ = false;
            switch (c) {
                case 'u': unicode_remaining_ = 4; break;
                case '"': case '\\': case '/': case 'b': case 'f': case 'n': case 'r': case 't': break;
                default: state_ = State::invalid; break;
            }
            return;
        }
        if (c == '\\') escaped_ = true;
        else if (c == '"') state_ = state_ == State::key_string ? State::colon : State::comma_or_end;
        else if (c < 0x20) state_ = State::invalid;
    }
};

inline bool json_object_complete(std::string_view output) {
    JsonCompletion scanner;
    return scanner.feed(output);
}

} // namespace webtoon
