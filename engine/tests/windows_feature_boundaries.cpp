// Exercise Windows feature refusals through the exported DLL boundary, and the
// film edge / date imprint that were ported (they render instead).
#include "spektrafilm/spk_engine.h"

#include <array>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

void require(bool condition, const std::string& message) {
    if (!condition) throw std::runtime_error(message);
}

std::string params(spk_session* session) {
    char* json = nullptr;
    require(spk_get_params(session, &json) == SPK_OK && json, "get_params failed");
    std::string result(json);
    spk_string_free(json);
    return result;
}

void render(spk_engine* engine, spk_session* session) {
    spk_result result{};
    const spk_status status = spk_render(session, "full", &result);
    const bool valid = status == SPK_OK && result.rgba16 && result.texture &&
                       result.width == 32 && result.height == 16;
    const std::string error = spk_last_error(engine);
    spk_result_free(&result);
    require(valid, "default render failed after a refused request: " + error);
}

}  // namespace

int main(int argc, char** argv) {
    if (argc != 2) { std::cerr << "usage: spk_windows_feature_boundaries resources_dir\n"; return 2; }
    try {
        std::unique_ptr<spk_engine, decltype(&spk_engine_destroy)> engine(
            spk_engine_create(argv[1], nullptr), spk_engine_destroy);
        require(bool(engine), std::string("engine creation failed: ") + spk_last_error(nullptr));
        const char* capabilities = spk_capabilities(engine.get());
        require(capabilities && std::string(capabilities).find("unsupported_features") != std::string::npos,
                "Windows capabilities must disclose unported effects");
        {
            const std::string caps(capabilities);
            const size_t list = caps.find("unsupported_features");
            const std::string tail = caps.substr(list, caps.find(']', list) - list);
            require(tail.find("overscan") == std::string::npos && tail.find("date_imprint") == std::string::npos,
                    "the ported film edge / date imprint are still listed as unsupported: " + tail);
        }

        std::vector<float> pixels(32 * 16 * 3);
        for (size_t i = 0; i < pixels.size(); ++i) pixels[i] = 0.025f + float(i % 31) * 0.025f;
        const spk_image input{pixels.data(), 32, 16, 3};
        std::unique_ptr<spk_session, decltype(&spk_session_release)> session(
            spk_open(engine.get(), &input, nullptr, nullptr), spk_session_release);
        require(bool(session), std::string("default open failed: ") + spk_last_error(engine.get()));
        render(engine.get(), session.get());
        const std::string before = params(session.get());

        struct Feature { const char* key; const char* label; };
        const std::array<Feature, 3> features{{
            {"digital_intermediate", "digital intermediate"},
            {"scene_latitude_active", "scene latitude mapping"},
            {"contrast_mask_active", "contrast mask"},
        }};
        for (const Feature& feature : features) {
            // Include a supported change to catch partial application before
            // the unsupported field is rejected.
            const std::string delta = std::string("{\"") + feature.key +
                                      "\":true,\"print_exposure\":1.25}";
            const spk_status status = spk_set_params(session.get(), delta.c_str(), nullptr);
            const std::string error = spk_last_error(engine.get());
            require(status == SPK_ERR_USER && error.find(feature.label) != std::string::npos &&
                    error.find("Windows Vulkan backend") != std::string::npos,
                    std::string("unclear feature refusal: ") + feature.key + ": " + error);
            require(params(session.get()) == before, std::string("refused delta mutated params: ") + feature.key);
            render(engine.get(), session.get());

            spk_session* rejected = spk_open(engine.get(), &input, delta.c_str(), nullptr);
            const std::string open_error = spk_last_error(engine.get());
            if (rejected) spk_session_release(rejected);
            require(!rejected && open_error.find(feature.label) != std::string::npos,
                    std::string("open accepted an unported effect: ") + feature.key);
        }

        spk_result di{};
        char* di_json = nullptr;
        const spk_status di_status = spk_render_digital_intermediate(session.get(), &di, &di_json);
        const std::string di_error = spk_last_error(engine.get());
        const bool empty = !di.texture && !di.rgba16 && !di_json;
        spk_result_free(&di);
        spk_string_free(di_json);
        require(di_status == SPK_ERR_USER && empty &&
                di_error.find("digital intermediate") != std::string::npos,
                "DI API did not return a clear refusal and empty result");
        require(params(session.get()) == before, "DI refusal mutated the session");
        render(engine.get(), session.get());

        const float* mask = nullptr;
        uint32_t width = 0, height = 0;
        require(spk_contrast_mask_field(session.get(), "full", &mask, &width, &height) == SPK_OK &&
                !mask && width == 0 && height == 0, "disabled contrast mask must have an empty field");

        // Latitude analysis uses the existing pipeline; only applying its
        // mapping is unported. This also exercises probe_medium's readback.
        char* latitude_json = nullptr;
        const spk_status latitude_status = spk_scene_latitude(session.get(), nullptr, &latitude_json);
        const std::string latitude_error = spk_last_error(engine.get());
        const bool latitude_valid = latitude_json &&
            std::string(latitude_json).find("\"medium\"") != std::string::npos &&
            std::string(latitude_json).find("\"scene\"") != std::string::npos;
        spk_string_free(latitude_json);
        require(latitude_status == SPK_OK && latitude_valid, "latitude analysis failed: " + latitude_error);
        require(params(session.get()) == before, "latitude analysis mutated the session");
        render(engine.get(), session.get());

        // Ported: the film edge returns the whole film canvas -- larger than
        // the frame, with a gate geometry -- and the date alone keeps the frame.
        // The frame is the 135 gate's shape (36.25 x 24.3), as the app crops it.
        std::vector<float> gate(145 * 97 * 3);
        for (size_t i = 0; i < gate.size(); ++i) gate[i] = 0.02f + float(i % 47) * 0.01f;
        const spk_image gate_input{gate.data(), 145, 97, 3};
        std::unique_ptr<spk_session, decltype(&spk_session_release)> film(
            spk_open(engine.get(), &gate_input, nullptr, nullptr), spk_session_release);
        require(bool(film), std::string("gate-shaped open failed: ") + spk_last_error(engine.get()));
        auto render_size = [&](const char* delta, uint32_t& w, uint32_t& h) {
            require(spk_set_params(film.get(), delta, nullptr) == SPK_OK,
                    std::string("ported effect refused: ") + delta + ": " + spk_last_error(engine.get()));
            spk_result result{};
            const spk_status status = spk_render(film.get(), "full", &result);
            const std::string error = spk_last_error(engine.get());
            w = result.width; h = result.height;
            const bool ok = status == SPK_OK && result.rgba16;
            spk_result_free(&result);
            require(ok, std::string("ported effect did not render: ") + delta + ": " + error);
        };
        uint32_t w = 0, h = 0;
        render_size("{\"overscan_active\":true,\"overscan_format\":\"135\"}", w, h);
        require(w > 145 && h > 97, "the film edge did not grow the canvas: " + std::to_string(w) + "x" + std::to_string(h));
        char* geometry = nullptr;
        require(spk_overscan_geometry(film.get(), &geometry) == SPK_OK && geometry &&
                std::string(geometry).find("\"valid\":true") != std::string::npos,
                "overscan geometry is not valid with the film edge on");
        spk_string_free(geometry);
        render_size("{\"overscan_active\":true,\"date_imprint_active\":true,\"date_imprint_text\":\"'26 10 19\"}", w, h);
        render_size("{\"overscan_active\":false}", w, h);
        require(w == 145 && h == 97, "the date alone changed the frame size");
        render_size("{\"date_imprint_active\":false}", w, h);
        std::cout << "Windows feature boundaries passed (3 refusals, DI refusal, mask off, latitude analysis, "
                     "film edge and date render)\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
