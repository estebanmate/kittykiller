const { onRequest } = require("firebase-functions/v2/https");
const { GoogleGenerativeAI, SchemaType } = require("@google/generative-ai");

// IMPORTANTE: Usaremos variables de entorno para la API KEY
const genAI = new GoogleGenerativeAI(process.env.GEMINI_API_KEY);

exports.procesarDocumentoTest = onRequest({ cors: true, secrets: ["GEMINI_API_KEY"] }, async (req, res) => {
  // CORS ya está habilitado con { cors: true } arriba, pero por seguridad doble:
  if (req.method === "OPTIONS") {
    res.set("Access-Control-Allow-Methods", "POST");
    res.set("Access-Control-Allow-Headers", "Content-Type");
    res.status(204).send("");
    return;
  }

  try {
    const { mode, content } = req.body;

    if (!content) {
      res.status(400).json({ error: "Falta el contenido del documento." });
      return;
    }

    const model = genAI.getGenerativeModel({
      model: "gemini-2.5-flash-lite",
      generationConfig: {
        responseMimeType: "application/json",
        responseSchema: {
          type: SchemaType.ARRAY,
          items: {
            type: SchemaType.OBJECT,
            properties: {
              id: { type: SchemaType.INTEGER },
              pregunta: { type: SchemaType.STRING },
              opciones: {
                type: SchemaType.ARRAY,
                items: { type: SchemaType.STRING }
              },
              respuesta_correcta: { type: SchemaType.STRING },
            },
            required: ["pregunta", "opciones", "respuesta_correcta"],
          },
        },
      },
    });

    let systemInstruction = "";
    if (mode === "extract") {
      systemInstruction = `
        Eres un asistente experto en digitalización de exámenes.
        Identifica preguntas, opciones y respuestas del texto.
        Si no hay respuesta marcada, dedúcela.
        IMPORTANTE: El campo 'respuesta_correcta' DEBE ser ÚNICAMENTE la letra (A, B, C o D). No incluyas el texto de la opción.
        Devuelve JSON limpio.
      `;
    } else {
      systemInstruction = `
        Genera un examen tipo test de 20 preguntas basado en el texto.
        4 opciones por pregunta, una correcta.
        IMPORTANTE: El campo 'respuesta_correcta' DEBE ser ÚNICAMENTE la letra (A, B, C o D).
        Nivel: Técnico Auxiliar (TCAE).
      `;
    }

    const chat = model.startChat({
      history: [{ role: "user", parts: [{ text: systemInstruction }] }]
    });

    const result = await chat.sendMessage(content);
    const jsonResponse = JSON.parse(result.response.text());

    res.status(200).json({
      success: true,
      data: jsonResponse
    });

  } catch (error) {
    console.error("Error:", error);
    res.status(500).json({ success: false, error: error.message });
  }
});
