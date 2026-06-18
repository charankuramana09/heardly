package com.otter.controller;

import com.otter.domain.IntegrationType;
import com.otter.domain.User;
import com.otter.service.IntegrationService;
import com.otter.service.UserService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/integrations")
public class IntegrationController {

    private final IntegrationService integrationService;
    private final UserService userService;

    public IntegrationController(IntegrationService integrationService, UserService userService) {
        this.integrationService = integrationService;
        this.userService = userService;
    }

    @PostMapping("/{type}/connect")
    public String connect(@PathVariable String type,
                          @RequestParam("target") String target,
                          @AuthenticationPrincipal UserDetails principal,
                          RedirectAttributes ra) {
        User u = userService.getByEmail(principal.getUsername());
        IntegrationType t = parse(type);
        if (t == null) {
            ra.addFlashAttribute("integrationError", "Unknown integration: " + type);
            return "redirect:/integrations";
        }
        try {
            integrationService.connect(u.getId(), t, target);
            ra.addFlashAttribute("integrationMessage", label(t) + " connected. Summaries will be sent automatically.");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("integrationError", e.getMessage());
        }
        return "redirect:/integrations";
    }

    @PostMapping("/{type}/disconnect")
    public String disconnect(@PathVariable String type,
                             @AuthenticationPrincipal UserDetails principal,
                             RedirectAttributes ra) {
        User u = userService.getByEmail(principal.getUsername());
        IntegrationType t = parse(type);
        if (t != null) {
            integrationService.disconnect(u.getId(), t);
            ra.addFlashAttribute("integrationMessage", label(t) + " disconnected.");
        }
        return "redirect:/integrations";
    }

    @PostMapping("/{type}/test")
    public String test(@PathVariable String type,
                       @AuthenticationPrincipal UserDetails principal,
                       RedirectAttributes ra) {
        User u = userService.getByEmail(principal.getUsername());
        IntegrationType t = parse(type);
        if (t == null) {
            ra.addFlashAttribute("integrationError", "Unknown integration: " + type);
            return "redirect:/integrations";
        }
        try {
            integrationService.sendTest(u.getId(), t);
            ra.addFlashAttribute("integrationMessage", "Test message sent via " + label(t) + ".");
        } catch (Exception e) {
            ra.addFlashAttribute("integrationError", "Test failed: " + e.getMessage());
        }
        return "redirect:/integrations";
    }

    private IntegrationType parse(String type) {
        try {
            return IntegrationType.valueOf(type.trim().toUpperCase());
        } catch (Exception e) {
            return null;
        }
    }

    private String label(IntegrationType t) {
        return t == IntegrationType.GMAIL ? "Email" : "Slack";
    }
}
