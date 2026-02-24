package com.easyshell.server.ai.learning;

import com.easyshell.server.ai.config.AgenticConfigService;
import com.easyshell.server.ai.model.entity.AiSopTemplate;
import com.easyshell.server.ai.orchestrator.ExecutionPlan;
import com.easyshell.server.ai.repository.AiSopTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SopRetriever — SOP matching and plan conversion")
class SopRetrieverTest {

    @Mock
    private VectorStore vectorStore;
    @Mock
    private AiSopTemplateRepository sopTemplateRepository;
    @Mock
    private AgenticConfigService configService;

    private SopRetriever retriever;

    @BeforeEach
    void setUp() {
        retriever = new SopRetriever(vectorStore, sopTemplateRepository, configService);
    }

    @Nested
    @DisplayName("findMatchingSop")
    class FindMatchingSop {

        @Test
        void disabledSop_returnsEmpty() {
            when(configService.getBoolean(eq("ai.sop.enabled"), anyBoolean())).thenReturn(false);
            assertThat(retriever.findMatchingSop("restart nginx", 1L)).isEmpty();
        }

        @Test
        void nullMessage_returnsEmpty() {
            when(configService.getBoolean(eq("ai.sop.enabled"), anyBoolean())).thenReturn(true);
            assertThat(retriever.findMatchingSop(null, 1L)).isEmpty();
        }

        @Test
        void blankMessage_returnsEmpty() {
            when(configService.getBoolean(eq("ai.sop.enabled"), anyBoolean())).thenReturn(true);
            assertThat(retriever.findMatchingSop("   ", 1L)).isEmpty();
        }

        @Test
        void noVectorResults_returnsEmpty() {
            when(configService.getBoolean(eq("ai.sop.enabled"), anyBoolean())).thenReturn(true);
            when(configService.getDouble(eq("ai.sop.confidence-threshold"), anyDouble())).thenReturn(0.7);
            when(configService.getInt(eq("ai.sop.min-success-count"), anyInt())).thenReturn(3);
            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

            assertThat(retriever.findMatchingSop("restart nginx", 1L)).isEmpty();
        }

        @Test
        void matchingSop_returned() {
            when(configService.getBoolean(eq("ai.sop.enabled"), anyBoolean())).thenReturn(true);
            when(configService.getDouble(eq("ai.sop.confidence-threshold"), anyDouble())).thenReturn(0.7);
            when(configService.getInt(eq("ai.sop.min-success-count"), anyInt())).thenReturn(3);

            Document doc = new Document("emb-1", "nginx restart sop", Map.of("sopId", "42"));
            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc));

            AiSopTemplate sop = AiSopTemplate.builder()
                    .id(42L).title("Nginx Restart SOP").enabled(true)
                    .confidence(0.9).successCount(5).userId(null)
                    .stepsJson("{\"steps\":[],\"estimated_risk\":\"LOW\"}")
                    .build();
            when(sopTemplateRepository.findById(42L)).thenReturn(Optional.of(sop));

            Optional<AiSopTemplate> result = retriever.findMatchingSop("restart nginx", 1L);
            assertThat(result).isPresent();
            assertThat(result.get().getTitle()).isEqualTo("Nginx Restart SOP");
        }
    }

    @Nested
    @DisplayName("sopToPlan")
    class SopToPlan {

        @Test
        void validStepsJson_returnsPlan() {
            AiSopTemplate sop = AiSopTemplate.builder()
                    .id(1L).title("Test SOP")
                    .stepsJson("{\"steps\":[{\"index\":0,\"description\":\"check status\",\"agent\":\"explore\"},{\"index\":1,\"description\":\"restart service\",\"agent\":\"execute\"}],\"estimated_risk\":\"MEDIUM\"}")
                    .build();

            ExecutionPlan plan = retriever.sopToPlan(sop);
            assertThat(plan).isNotNull();
            assertThat(plan.getSummary()).contains("[SOP]").contains("Test SOP");
            assertThat(plan.getSteps()).hasSize(2);
            assertThat(plan.getSteps().get(0).getDescription()).isEqualTo("check status");
            assertThat(plan.getSteps().get(1).getAgent()).isEqualTo("execute");
            assertThat(plan.getEstimatedRisk()).isEqualTo("MEDIUM");
            assertThat(plan.isRequiresConfirmation()).isTrue();
        }

        @Test
        void emptySteps_returnsNull() {
            AiSopTemplate sop = AiSopTemplate.builder()
                    .id(2L).title("Empty SOP")
                    .stepsJson("{\"steps\":[]}")
                    .build();

            assertThat(retriever.sopToPlan(sop)).isNull();
        }

        @Test
        void invalidJson_returnsNull() {
            AiSopTemplate sop = AiSopTemplate.builder()
                    .id(3L).title("Bad SOP")
                    .stepsJson("{bad json}")
                    .build();

            assertThat(retriever.sopToPlan(sop)).isNull();
        }
    }
}
